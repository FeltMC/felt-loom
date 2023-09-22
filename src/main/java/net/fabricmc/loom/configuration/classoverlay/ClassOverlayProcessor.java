/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021-2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.classoverlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.google.gson.JsonPrimitive;

import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.api.processor.MinecraftJarProcessor;
import net.fabricmc.loom.api.processor.ProcessorContext;
import net.fabricmc.loom.api.processor.SpecContext;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.Pair;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.fmj.FabricModJson;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class ClassOverlayProcessor implements MinecraftJarProcessor<ClassOverlayProcessor.Spec> {
	private static final Logger LOGGER = LoggerFactory.getLogger(ClassOverlayProcessor.class);

	private final String name;

	@Inject
	public ClassOverlayProcessor(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public @Nullable ClassOverlayProcessor.Spec buildSpec(SpecContext context) {
		List<OverlayedClass> overlayedClasses = new ArrayList<>();

		overlayedClasses.addAll(OverlayedClass.fromMods(context.localMods()));
		// Find the injected interfaces from mods that are both on the compile and runtime classpath.
		// Runtime is also required to ensure that the interface and it's impl is present when running the mc jar.

		overlayedClasses.addAll(OverlayedClass.fromMods(context.modDependenciesCompileRuntime()));

		if (overlayedClasses.isEmpty()) {
			return null;
		}

		return new Spec(overlayedClasses);
	}

	public record Spec(List<OverlayedClass> overlayedClasses) implements MinecraftJarProcessor.Spec {
	}

	@Override
	public void processJar(Path jar, Spec spec, ProcessorContext context) throws IOException {
		// Remap from intermediary->named
		final MemoryMappingTree mappings = context.getMappings();
		final int intermediaryIndex = mappings.getNamespaceId(MappingsNamespace.INTERMEDIARY.toString());
		final int namedIndex = mappings.getNamespaceId(MappingsNamespace.NAMED.toString());
		final List<OverlayedClass> remappedOverlayedClasses = spec.overlayedClasses().stream()
				.map(overlayedClass -> remap(overlayedClass, s -> mappings.mapClassName(s, intermediaryIndex, namedIndex)))
				.toList();

		try {
			ZipUtils.transform(jar, getTransformers(remappedOverlayedClasses));
		} catch (IOException e) {
			throw new RuntimeException("Failed to apply overlays to " + jar, e);
		}
	}

	private OverlayedClass remap(OverlayedClass in, Function<String, String> remapper) {
		Function<Type, Type> typeRemapper = inType -> {
			if(inType.getSort() != Type.OBJECT && inType.getSort() != Type.ARRAY)
				return inType;

			Type remappableType;
			int dimension;
			if(inType.getSort() == Type.ARRAY) {
				dimension = inType.getDimensions();
				remappableType = inType.getElementType();
			} else {
				dimension = 0;
				remappableType = inType;
			}
			String remappedClassName = remapper.apply(remappableType.getInternalName());
			return Type.getObjectType("[".repeat(dimension) + Type.getObjectType(remappedClassName).getDescriptor());
		};
		return new OverlayedClass(
				in.modId(),
				remapper.apply(in.targetName()),
				in.overlays().stream().map(o -> o.remap(typeRemapper)).collect(Collectors.toList())
		);
	}

	private List<Pair<String, ZipUtils.UnsafeUnaryOperator<byte[]>>> getTransformers(List<OverlayedClass> overlayedClasses) {
		return overlayedClasses.stream()
				.collect(Collectors.groupingBy(OverlayedClass::targetName))
				.entrySet()
				.stream()
				.map(entry -> {
					final String zipEntry = entry.getKey().replaceAll("\\.", "/") + ".class";
					return new Pair<>(zipEntry, getTransformer(entry.getValue()));
				}).toList();
	}

	private static int getAccess(String visibility, boolean isStatic) {
		int baseFlag = switch (visibility.toLowerCase(Locale.ROOT)) {
			case "public" -> Opcodes.ACC_PUBLIC;
			case "private" -> Opcodes.ACC_PRIVATE;
			case "protected" -> Opcodes.ACC_PROTECTED;
			default -> throw new IllegalArgumentException("unknown visibility: " + visibility);
		};
		if(isStatic)
			baseFlag |= Opcodes.ACC_STATIC;
		return baseFlag;
	}

	private static void mergeOverlayedClasses(int asmVersion, ClassNode classNode, List<OverlayedClass> overlayedClasses) {
		for(OverlayedClass overlayedClass : overlayedClasses) {
			var overlayData = overlayedClass.overlays();
			for(Overlay overlay : overlayData) {
				if(overlay instanceof FieldOverlay fOverlay) {
					classNode.fields.add(new FieldNode(fOverlay.accessFlag(), fOverlay.name(), fOverlay.descriptor().getDescriptor(), null, null));
				} else if(overlay instanceof MethodOverlay mOverlay) {
					MethodNode mNode = new MethodNode(asmVersion);
					mNode.name = mOverlay.name();
					mNode.desc = mOverlay.methodType.getDescriptor();
					mNode.access = mOverlay.accessFlag();
					//  NEW java/lang/AssertionError
					//  DUP
					//  INVOKESPECIAL java/lang/AssertionError.<init> ()V
					//  ATHROW
					mNode.maxStack = 2;
					mNode.maxLocals = 0;
					mNode.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/AssertionError"));
					mNode.instructions.add(new InsnNode(Opcodes.DUP));
					mNode.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V"));
					mNode.instructions.add(new InsnNode(Opcodes.ATHROW));
					classNode.methods.add(mNode);
				}
			}
		}
	}

	private ZipUtils.UnsafeUnaryOperator<byte[]> getTransformer(List<OverlayedClass> overlayedClasses) {
		return input -> {
			final ClassReader reader = new ClassReader(input);
			final ClassNode node = new ClassNode();
			reader.accept(node, 0);
			mergeOverlayedClasses(Constants.ASM_VERSION, node, overlayedClasses);
			final ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		};
	}

	@Override
	public MappingsProcessor<Spec> processMappings() {
		return (mappings, spec, context) -> {
			if (!MappingsNamespace.INTERMEDIARY.toString().equals(mappings.getSrcNamespace())) {
				throw new IllegalStateException("Mapping tree must have intermediary src mappings not " + mappings.getSrcNamespace());
			}

			Map<String, List<OverlayedClass>> map = spec.overlayedClasses().stream()
					.collect(Collectors.groupingBy(OverlayedClass::targetName));

			for (Map.Entry<String, List<OverlayedClass>> entry : map.entrySet()) {
				final String className = entry.getKey();
				final List<OverlayedClass> overlayedClasses = entry.getValue();

				MappingTree.ClassMapping classMapping = mappings.getClass(className);

				if (classMapping == null) {
					final String modIds = overlayedClasses.stream().map(OverlayedClass::modId).distinct().collect(Collectors.joining(","));
					LOGGER.warn("Failed to find class ({}) to add overlays from mod(s) ({})", className, modIds);
					continue;
				}

				classMapping.setComment(appendComment(classMapping.getComment(), overlayedClasses));
			}

			return true;
		};
	}

	private static String appendComment(String comment, List<OverlayedClass> overlayedClasses) {
		if (overlayedClasses.isEmpty()) {
			return comment;
		}

		var commentBuilder = comment == null ? new StringBuilder() : new StringBuilder(comment);

		for (OverlayedClass overlayedClass : overlayedClasses) {
			String iiComment = "<p>Class {@link %s} overlayed by mod %s</p>".formatted(overlayedClass.targetName() .replace('/', '.').replace('$', '.'), overlayedClass.modId());

			if (commentBuilder.indexOf(iiComment) == -1) {
				if (commentBuilder.isEmpty()) {
					commentBuilder.append(iiComment);
				} else {
					commentBuilder.append('\n').append(iiComment);
				}
			}
		}

		return comment;
	}

	interface Overlay {
		int accessFlag();

		String name();

		Overlay remap(Function<Type, Type> remapper);
	}

	record FieldOverlay(String name, Type descriptor, int accessFlag) implements Overlay {
		@Override
		public Overlay remap(Function<Type, Type> remapper) {
			return new FieldOverlay(name, remapper.apply(descriptor), accessFlag);
		}
	}

	record MethodOverlay(String name, Type methodType, int accessFlag) implements Overlay {
		@Override
		public Overlay remap(Function<Type, Type> remapper) {
			Type[] oldArgumentTypes = methodType.getArgumentTypes();
			Type[] newArgumentTypes = new Type[oldArgumentTypes.length];
			for(int i = 0; i < newArgumentTypes.length; i++) {
				newArgumentTypes[i] = remapper.apply(oldArgumentTypes[i]);
			}
			Type newReturnType = remapper.apply(methodType.getReturnType());
			Type newMethodType = Type.getMethodType(newReturnType, newArgumentTypes);
			return new MethodOverlay(name, newMethodType, accessFlag);
		}
	}

	private record OverlayedClass(String modId, String targetName, List<Overlay> overlays) {
		public static List<OverlayedClass> fromMod(FabricModJson fabricModJson) {
			final String modId = fabricModJson.getId();
			final JsonElement jsonElement = fabricModJson.getCustom(Constants.CustomModJsonKeys.FELT_LOOM_OVERLAYS);

			if (jsonElement == null) {
				return Collections.emptyList();
			}

			final JsonObject addedOverlays = jsonElement.getAsJsonObject();

			final List<OverlayedClass> result = new ArrayList<>();

			for (String className : addedOverlays.keySet()) {
				final List<Overlay> parsedOverlays = new ArrayList<>();
				final JsonArray classOverlays = addedOverlays.getAsJsonArray(className);
				for(JsonElement e : classOverlays) {
					if(!(e instanceof JsonObject overlay))
						continue;
					String type = overlay.getAsJsonPrimitive("type").getAsString();
					String signature = overlay.getAsJsonPrimitive("signature").getAsString();
					String visibility = overlay.has("visibility") ? overlay.getAsJsonPrimitive("visibility").getAsString() : "public";
					boolean isStatic = overlay.has("static") && overlay.get("static").getAsBoolean();
					if(type.equals("field")) {
						String[] splitSignature = signature.split(":", 2);
						parsedOverlays.add(new FieldOverlay(
								splitSignature[0],
								Type.getType(splitSignature[1]),
								getAccess(visibility, isStatic)
						));
					} else if(type.equals("method")) {
						String name = signature.substring(0, signature.indexOf('('));
						String dsc = signature.substring(name.length());
						Type methodType = Type.getMethodType(dsc);
						parsedOverlays.add(new MethodOverlay(
								name,
								methodType,
								getAccess(visibility, isStatic)
						));
					} else
						throw new IllegalArgumentException("unknown overlay type: " + type);
				}
				result.add(new OverlayedClass(modId, className, parsedOverlays));
			}

			return result;
		}

		public static List<OverlayedClass> fromMods(List<FabricModJson> fabricModJsons) {
			return fabricModJsons.stream()
					.map(OverlayedClass::fromMod)
					.flatMap(List::stream)
					.toList();
		}
	}
}

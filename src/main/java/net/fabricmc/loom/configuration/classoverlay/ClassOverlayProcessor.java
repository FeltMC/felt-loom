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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
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
		return new OverlayedClass(
				in.modId(),
				remapper.apply(in.targetName()),
				remapper.apply(in.overlayName())
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

	private ZipUtils.UnsafeUnaryOperator<byte[]> getTransformer(List<OverlayedClass> overlayedClasses) {
		return input -> {
			final ClassReader reader = new ClassReader(input);
			final ClassWriter writer = new ClassWriter(0);
			final ClassVisitor classVisitor = new InjectingClassVisitor(Constants.ASM_VERSION, writer, overlayedClasses);
			reader.accept(classVisitor, 0);
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
			String iiComment = "<p>Class {@link %s} overlayed by mod %s</p>".formatted(overlayedClass.overlayName().replace('/', '.').replace('$', '.'), overlayedClass.modId());

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

	private record OverlayedClass(String modId, String targetName, String overlayName) {
		public static List<OverlayedClass> fromMod(FabricModJson fabricModJson) {
			final String modId = fabricModJson.getId();
			final JsonElement jsonElement = fabricModJson.getCustom(Constants.CustomModJsonKeys.FELT_LOOM_OVERLAYS);

			if (jsonElement == null) {
				return Collections.emptyList();
			}

			final JsonObject addedOverlays = jsonElement.getAsJsonObject();

			final List<OverlayedClass> result = new ArrayList<>();

			for (String className : addedOverlays.keySet()) {
				final JsonArray ifaceNames = addedOverlays.getAsJsonArray(className);

				for (JsonElement ifaceName : ifaceNames) {
					result.add(new OverlayedClass(modId, className, ifaceName.getAsString()));
				}
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

	private static class InjectingClassVisitor extends ClassVisitor {
		private static final int INTERFACE_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;

		private final List<OverlayedClass> overlayedClasses;
		private final Set<String> knownInnerClasses = new HashSet<>();

		InjectingClassVisitor(int asmVersion, ClassWriter writer, List<OverlayedClass> overlayedClasses) {
			super(asmVersion, writer);
			this.overlayedClasses = overlayedClasses;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			Set<String> modifiedInterfaces = new LinkedHashSet<>(interfaces.length + overlayedClasses.size());
			Collections.addAll(modifiedInterfaces, interfaces);

			for (OverlayedClass overlayedClass : overlayedClasses) {
				modifiedInterfaces.add(overlayedClass.overlayName());
			}

			// See JVMS: https://docs.oracle.com/javase/specs/jvms/se17/html/jvms-4.html#jvms-ClassSignature
			if (signature != null) {
				var resultingSignature = new StringBuilder(signature);

				for (OverlayedClass overlayedClass : overlayedClasses) {
					String superinterfaceSignature = "L" + overlayedClass.overlayName() + ";";

					if (resultingSignature.indexOf(superinterfaceSignature) == -1) {
						resultingSignature.append(superinterfaceSignature);
					}
				}

				signature = resultingSignature.toString();
			}

			super.visit(version, access, name, signature, superName, modifiedInterfaces.toArray(new String[0]));
		}

		@Override
		public void visitInnerClass(final String name, final String outerName, final String innerName, final int access) {
			this.knownInnerClasses.add(name);
			super.visitInnerClass(name, outerName, innerName, access);
		}

		@Override
		public void visitEnd() {
			// inject any necessary inner class entries
			// this may produce technically incorrect bytecode cuz we don't know the actual access flags for inner class entries
			// but it's hopefully enough to quiet some IDE errors
			for (final OverlayedClass itf : overlayedClasses) {
				if (this.knownInnerClasses.contains(itf.overlayName())) {
					continue;
				}

				int simpleNameIdx = itf.overlayName().lastIndexOf('/');
				final String simpleName = simpleNameIdx == -1 ? itf.overlayName() : itf.overlayName().substring(simpleNameIdx + 1);
				int lastIdx = -1;
				int dollarIdx = -1;

				// Iterate through inner class entries starting from outermost to innermost
				while ((dollarIdx = simpleName.indexOf('$', dollarIdx + 1)) != -1) {
					if (dollarIdx - lastIdx == 1) {
						continue;
					}

					// Emit the inner class entry from this to the last one
					if (lastIdx != -1) {
						final String outerName = itf.overlayName().substring(0, simpleNameIdx + 1 + lastIdx);
						final String innerName = simpleName.substring(lastIdx + 1, dollarIdx);
						super.visitInnerClass(outerName + '$' + innerName, outerName, innerName, INTERFACE_ACCESS);
					}

					lastIdx = dollarIdx;
				}

				// If we have a trailer to append
				if (lastIdx != -1 && lastIdx != simpleName.length()) {
					final String outerName = itf.overlayName().substring(0, simpleNameIdx + 1 + lastIdx);
					final String innerName = simpleName.substring(lastIdx + 1);
					super.visitInnerClass(outerName + '$' + innerName, outerName, innerName, INTERFACE_ACCESS);
				}
			}

			super.visitEnd();
		}
	}
}

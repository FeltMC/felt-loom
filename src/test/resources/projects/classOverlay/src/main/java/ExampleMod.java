import net.minecraft.block.Blocks;
import net.minecraft.block.Block;

import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
	@Override
	public void onInitialize() {
		Block.newStaticMethodThatDidNotExist();
		Block.anotherNewStaticMethodThatDidNotExist();
		Block.newStaticFieldThatDidNotExist = 0;
		Block.anotherNewStaticFieldThatDidNotExist = 1;
		Blocks.AIR.newMethodThatDidNotExist();
		Blocks.AIR.anotherNewMethodThatDidNotExist();
		Blocks.AIR.newFieldThatDidNotExist = 2;
		Blocks.AIR.anotherNewFieldThatDidNotExist = 3;
	}
}

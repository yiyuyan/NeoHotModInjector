package cn.ksmcbrigade.nhmj.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class InjectorConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<MixinTransformMode> MIXIN_TRANSFORM_MODE = BUILDER.comment("WARN: NATIVE MODE is only for Windows OS x64 with JetBrainsRuntime 21.").defineEnum("mixinTransformMode",MixinTransformMode.TRAMPOLINE);
    //public static final ModConfigSpec.BooleanValue INJECT_CONFIRM_SCREEN = BUILDER.comment("When it's true,it will show a confirm screen.").define("confirm_screen",true);

    public static final ModConfigSpec CONFIG = BUILDER.build();

    public enum MixinTransformMode{
        TRAMPOLINE,
        NATIVE
    }
}

package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.UnsafeUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static cn.ksmcbrigade.mr.utils.UnsafeUtils.getFieldValue;

public class ModuleUtilsAccess {

    public static final MethodHandles.Lookup lookup = getFieldValue(MethodHandles.Lookup.class, "IMPL_LOOKUP", MethodHandles.Lookup.class);

    public static void implAddReads(Module module,Module other) throws Throwable {
        MethodHandle methodHandle = lookup.findVirtual(Module.class,"implAddReads", MethodType.methodType(Void.TYPE,Module.class));
        methodHandle.invoke(module,other);
    }

    public static void addReads(Module module,Module other) throws Throwable {
        MethodHandle methodHandle = lookup.findStatic(Module.class,"addReads0", MethodType.methodType(void.class, Module.class, Module.class));
        methodHandle.invoke(module,other);
    }

    public static void addIntoReads(Module module,Module other) throws NoSuchFieldException, IllegalAccessException {
        Field field = Module.class.getDeclaredField("reads");
        field.setAccessible(true);
        Set<Module> reads = (Set<Module>) field.get(module);
        if(reads==null) reads = new HashSet<>();
        Set<Module> newReads = new HashSet<>(reads);
        newReads.add(module);
        field.set(module,newReads);
    }
}

package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.InstUtils;
import cn.ksmcbrigade.mr.utils.mixin.*;
import cn.ksmcbrigade.nhmj.NHMJMod;
import com.terraformersmc.mod_menu.ModMenu;
import com.terraformersmc.mod_menu.util.mod.neoforge.NeoforgeMod;
import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.*;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.UniqueModListBuilder;
import net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.ModValidator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.fml.loading.modscan.BackgroundScanHandler;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.tools.agent.MixinAgent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.module.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.rmi.UnexpectedException;
import java.util.*;
import java.util.stream.Collectors;

import static cn.ksmcbrigade.mr.utils.UnsafeUtils.getFieldValue;
import static cn.ksmcbrigade.mr.utils.UnsafeUtils.setFieldValue;

@SuppressWarnings({"UnstableApiUsage", "unchecked"})
public class Injector {

    public static JarModsDotTomlModFileReader reader = new JarModsDotTomlModFileReader();

    public static void inject(Path path) throws Throwable {
        JarContents jarContents = JarContents.of(path);
        IModFile modFile = reader.read(jarContents,ModFileDiscoveryAttributes.DEFAULT);
        Map<IModFile.Type, List<ModFile>> modFilesMap;
        List<ModFile> loadedFiles = new ArrayList<>();
        if(modFile instanceof ModFile modFile1) loadedFiles.add(modFile1);
        final UniqueModListBuilder modsUniqueListBuilder = new UniqueModListBuilder(loadedFiles);
        final UniqueModListBuilder.UniqueModListData uniqueModsData = modsUniqueListBuilder.buildUniqueList();

        //Grab the temporary results.
        //This allows loading to continue to a base state, in case dependency loading fails.
        modFilesMap = uniqueModsData.modFiles().stream()
                .collect(Collectors.groupingBy(IModFile::getType));
        ModValidator validator = new ModValidator(modFilesMap,List.of());
        validator.stage1Validation();
        BackgroundScanHandler backgroundScanHandler = validator.stage2Validation();

        Field addedF = DeferredMixinConfigRegistration.class.getDeclaredField("added");
        addedF.setAccessible(true);
        addedF.set(null,false);

        LoadingModList loadingModList = backgroundScanHandler.getLoadingModList();

        addToGameLayer(loadingModList);

        for (ModFileInfo file : loadingModList.getModFiles()) {
            for (String mixinConfig : file.getFile().getMixinConfigs()) {
                Mixins.addConfiguration(mixinConfig);
                IMixinConfig config = Mixins.getConfigs().stream().collect(Collectors.toMap(Config::getName, Config::getConfig)).get(mixinConfig);
                if(config!=null){
                    config.decorate(FabricUtil.KEY_MOD_ID,file.getMods().getFirst().getModId());

                    MixinConfigUtils.onSelect(config);
                    MixinConfigUtils.prepare(config,MixinAgentUtils.getFirstAgent());
                    MixinProcessorUtils.addIntoProcessor(MixinProcessorUtils.getProcessor(MixinAgentUtils.getFirstAgent()),config);

                    List<Class<?>> targetClasses = new ArrayList<>();
                    for (String unhandledTarget : MixinConfigUtils.getUnhandledTargets(config)) {
                        targetClasses.addAll(MixinUtils.getTargetClasses(Class.forName(unhandledTarget)));
                    }
                    Objects.requireNonNull(MixinAgentUtils.getInst()).redefineClasses(targetClasses.stream().map((c)-> {
                        try {
                            return new ClassDefinition(c,MixinTransformerUtils.transform(c));
                        } catch (UnmodifiableClassException e) {
                            NHMJMod.LOGGER.error("Failed to create class definition.",e);
                            try {
                                return new ClassDefinition(c, InstUtils.getClassBytes(MixinAgentUtils.getInst(),c));
                            } catch (UnmodifiableClassException ex) {
                                NHMJMod.LOGGER.error("Failed to get class bytes.",e);
                                return new ClassDefinition(c, MixinAgent.ERROR_BYTECODE);
                            }
                        }
                    }).toList().toArray(new ClassDefinition[0]));
                }
            }
        }

        List<ModContainer> containerList = loadingModList.getModFiles().stream()
                .map(ModFileInfo::getFile)
                .map((iModFile)->{
                    try {
                        Method method = ModLoader.class.getDeclaredMethod("buildMods", IModFile.class);
                        method.setAccessible(true);
                        return method.invoke(null,iModFile);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                })
                .<ModContainer>mapMulti((modContainers, consumer)->{
                    for (ModContainer container : (List<ModContainer>) modContainers) {
                        consumer.accept(container);
                    }
                })
                .toList();

        Field currentModFilesF = ModList.class.getDeclaredField("modFiles");
        Field currentModInfosF = ModList.class.getDeclaredField("sortedList");
        Field currentMods = ModList.class.getDeclaredField("mods");

        currentModFilesF.setAccessible(true);
        currentModInfosF.setAccessible(true);
        currentMods.setAccessible(true);

        ArrayList<ModFile> modFiles = new ArrayList<>();
        ArrayList<ModInfo> modInfos = new ArrayList<>();

        for (IModFileInfo iModFileInfo : ((List<IModFileInfo>) currentModFilesF.get(ModList.get()))) {
            modFiles.add((ModFile) iModFileInfo.getFile());
        }

        for (IModInfo info : ((List<IModInfo>) currentModInfosF.get(ModList.get()))) {
            modInfos.add((ModInfo) info);
        }

        ArrayList<ModContainer> modContainers = new ArrayList<>(((List<ModContainer>) currentMods.get(ModList.get())));

        if (modFile instanceof ModFile) {
            modFiles.add((ModFile) modFile);
        }
        else{
            throw new UnexpectedException("Wdf?");
        }
        modInfos.addAll(loadingModList.getMods());
        modContainers.addAll(containerList);

        Method setLoadedM = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
        setLoadedM.setAccessible(true);

        ModList.of(modFiles,modInfos);

        setLoadedM.invoke(ModList.get(),modContainers);

        Field modLoaderListF = ModLoader.class.getDeclaredField("modList");
        modLoaderListF.setAccessible(true);
        modLoaderListF.set(null,ModList.get());

        Method constructModM = ModContainer.class.getDeclaredMethod("constructMod");
        constructModM.setAccessible(true);

        DeferredWorkQueue workQueue = new DeferredWorkQueue("Mod Construction");
        for (ModContainer modContainer : containerList){
            ModLoadingContext.get().setActiveContainer(modContainer);
            constructModM.invoke(modContainer);
            modContainer.acceptEvent(new FMLConstructModEvent(modContainer, workQueue));
            ModMenu.MODS.put(modContainer.getModId(),new NeoforgeMod(modContainer));
            ModLoadingContext.get().setActiveContainer(null);
        }
    }

    private static void addToGameLayer(LoadingModList loadingModList) throws Throwable {
        for (ModFileInfo modFile : loadingModList.getModFiles()) {
            SecureJar secureJar = modFile.getFile().getSecureJar();
            String target = secureJar.name();

            Constructor<ResolvedModule> resolvedModuleConstructor = ResolvedModule.class.getDeclaredConstructor(Configuration.class,ModuleReference.class);
            resolvedModuleConstructor.setAccessible(true);

            Constructor<Module> moduleConstructor = Module.class.getDeclaredConstructor(ModuleLayer.class,ClassLoader.class, ModuleDescriptor.class, URI.class);
            moduleConstructor.setAccessible(true);

            JarModuleFinder finder = JarModuleFinder.of(secureJar);
            Set<ModuleReference> moduleReferences = finder.findAll();

            ModuleClassLoader moduleClassLoader = getModuleClassLoader();

            Map<String,Module> namedModule = getFieldValue(FMLLoader.getGameLayer(),"nameToModule",Map.class);
            Set<ResolvedModule> configurationModules = getFieldValue(FMLLoader.getGameLayer().configuration(),"modules",Set.class);
            Map<String,ResolvedModule> configurationNameToModules = getFieldValue(FMLLoader.getGameLayer().configuration(),"nameToModule",Map.class);

            Map<String, Object> resolvedRoots = getFieldValueWithTargetClass(ModuleClassLoader.class,moduleClassLoader,"resolvedRoots",Map.class);
            Map<String, ResolvedModule> packageLookup = getFieldValueWithTargetClass(ModuleClassLoader.class,moduleClassLoader,"packageLookup",Map.class);

            Set<Module> moduleSet = new HashSet<>(FMLLoader.getGameLayer().modules());

            HashSet<ResolvedModule> newConfigurationModules = new HashSet<>(configurationModules);

            HashMap<String, ResolvedModule> newConfigurationNameToModules = new HashMap<>(configurationNameToModules);

            HashMap<String, Object> newResolvedRoots = new HashMap<>();
            if (resolvedRoots != null) {
                newResolvedRoots = new HashMap<>(resolvedRoots);
            }
            else{
                NHMJMod.LOGGER.warn("The resolvedRoots is null.");
                for (ModuleReference reference : FMLLoader.getGameLayer().configuration().modules().stream().map(ResolvedModule::reference).toList()) {
                    newResolvedRoots.put(reference.descriptor().name(),reference);
                }
            }

            HashMap<String,Object> newPackageLookup = new HashMap<>();
            if(packageLookup!=null){
                newPackageLookup.putAll(packageLookup);
            }
            else{
                NHMJMod.LOGGER.warn("The packageLookup is null.");
                for (ResolvedModule module : FMLLoader.getGameLayer().configuration().modules()) {
                    for (String aPackage : module.reference().descriptor().packages()) {
                        newPackageLookup.put(aPackage,module);
                    }
                }
            }

            for (ModuleReference moduleReference : moduleReferences) {
                Module module = moduleConstructor.newInstance(FMLLoader.getGameLayer(),moduleClassLoader,moduleReference.descriptor(),moduleReference.location().orElseThrow());
                ResolvedModule resolvedModule = resolvedModuleConstructor.newInstance(FMLLoader.getGameLayer().configuration(),moduleReference);

                ModuleUtilsAccess.redefineModuleToAddAllReads(module);

                moduleSet.add(module);
                namedModule.put(moduleReference.descriptor().name(),module);
                namedModule.put(target,module);

                newConfigurationModules.add(resolvedModule);
                newConfigurationNameToModules.put(target,resolvedModule);

                newResolvedRoots.put(moduleReference.descriptor().name(),moduleReference);
                for (String aPackage : moduleReference.descriptor().packages()) {
                    newPackageLookup.put(aPackage,resolvedModule);
                }
            }

            setFieldValue(FMLLoader.getGameLayer(),"modules",moduleSet);
            setFieldValue(FMLLoader.getGameLayer(),"nameToModule",namedModule);

            setFieldValue(FMLLoader.getGameLayer().configuration(),"modules",newConfigurationModules);
            setFieldValue(FMLLoader.getGameLayer().configuration(),"nameToModule",newConfigurationNameToModules);

            setFieldValueWithTargetClass(ModuleClassLoader.class,moduleClassLoader,"resolvedRoots",newResolvedRoots);
            setFieldValueWithTargetClass(ModuleClassLoader.class,moduleClassLoader,"packageLookup",newPackageLookup);
        }
    }

    private static @NotNull ModuleClassLoader getModuleClassLoader() throws UnexpectedException {
        ClassLoader classLoader = FMLLoader.getGameLayer().findLoader("neoforge");
        ModuleClassLoader moduleClassLoader;
        if(classLoader instanceof ModuleClassLoader loader){
            moduleClassLoader = loader;
        }
        else{
            throw new UnexpectedException("How did you get there?");
        }
        return moduleClassLoader;
    }

    public static <T> T getFieldValueWithTargetClass(Class<?> targetClass, Object target, String fieldName, Class<T> clazz) {
        try {
            return getFieldValue(targetClass.getDeclaredField(fieldName), target, clazz);
        } catch (Throwable var4) {
            NHMJMod.LOGGER.error("Failed to get filed value by unsafe.",var4);
            return null;
        }
    }

    public static void setFieldValueWithTargetClass(Class<?> targetClass,Object target, String fieldName, Object value) {
        try {
            setFieldValue(targetClass.getDeclaredField(fieldName), target, value);
        } catch (Throwable var4) {
            NHMJMod.LOGGER.error("Failed to set filed value by unsafe.",var4);
        }

    }
}

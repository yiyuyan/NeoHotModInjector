package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.UnsafeUtils;
import cn.ksmcbrigade.nhmj.NHMJMod;
import com.terraformersmc.mod_menu.ModMenu;
import com.terraformersmc.mod_menu.util.mod.neoforge.NeoforgeMod;
import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.TransformingClassLoader;
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
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

import cn.ksmcbrigade.mr.utils.UnsafeUtils.*;

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

public class Injector {

    public static JarModsDotTomlModFileReader reader = new JarModsDotTomlModFileReader();

    public static void inject(Path path) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException, NoSuchFieldException, ClassNotFoundException, InstantiationException, UnexpectedException {
        JarContents jarContents = JarContents.of(path);
        IModFile modFile = reader.read(jarContents,ModFileDiscoveryAttributes.DEFAULT);
        Map<IModFile.Type, List<ModFile>> modFilesMap = new HashMap<>();
        List<ModFile> loadedFiles = new ArrayList<>();
        if(modFile instanceof ModFile modFile1) loadedFiles.add(modFile1);
        final UniqueModListBuilder modsUniqueListBuilder = new UniqueModListBuilder(loadedFiles);
        final UniqueModListBuilder.UniqueModListData uniqueModsData = modsUniqueListBuilder.buildUniqueList();

        //Grab the temporary results.
        //This allows loading to continue to a base state, in case dependency loading fails.
        modFilesMap = uniqueModsData.modFiles().stream()
                .collect(Collectors.groupingBy(IModFile::getType));
        loadedFiles = uniqueModsData.modFiles();
        ModValidator validator = new ModValidator(modFilesMap,List.of());
        validator.stage1Validation();
        BackgroundScanHandler backgroundScanHandler = validator.stage2Validation();

        Field addedF = DeferredMixinConfigRegistration.class.getDeclaredField("added");
        addedF.setAccessible(true);
        addedF.set(null,false);

        LoadingModList loadingModList = backgroundScanHandler.getLoadingModList();

        addToGameLayer(loadingModList);

        for (Module module : FMLLoader.getGameLayer().modules()) {
            NHMJMod.LOGGER.info("Module: name:{} - classloader:{} - annotations:{} - layer:{} - descriptor:{} - packages:{} - named:{} - module:{}",
                    module.getName(),
                    module.getClassLoader(),
                    module.getAnnotations(),
                    module.getLayer(),
                    module.getDescriptor(),
                    module.getPackages(),
                    module.isNamed(),
                    module);
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
                .<ModContainer>mapMulti((a,b)->{
                    System.out.println(a.getClass());
                    System.out.println(b.getClass());
                })
                .toList();

        for (ModFileInfo file : loadingModList.getModFiles()) {
            for (String mixinConfig : file.getFile().getMixinConfigs()) {
                Mixins.addConfiguration(mixinConfig);
                IMixinConfig config = Mixins.getConfigs().stream().collect(Collectors.toMap(Config::getName, Config::getConfig)).get(mixinConfig);
                if(config!=null){
                    config.decorate(FabricUtil.KEY_MOD_ID,file.getMods().get(0).getModId());
                }
            }
        }

        Field currentModFilesF = ModList.class.getDeclaredField("modFiles");
        Field currentModInfosF = ModList.class.getDeclaredField("sortedList");
        Field currentMods = ModList.class.getDeclaredField("mods");

        currentModFilesF.setAccessible(true);
        currentModInfosF.setAccessible(true);
        currentMods.setAccessible(true);

        ArrayList<ModFile> modFiles = new ArrayList<>();
        ArrayList<ModInfo> modInfos = new ArrayList<>();
        ArrayList<ModContainer> modContainers = new ArrayList<>();

        for (IModFileInfo iModFileInfo : ((List<IModFileInfo>) currentModFilesF.get(ModList.get()))) {
            modFiles.add((ModFile) iModFileInfo.getFile());
        }

        for (IModInfo info : ((List<IModInfo>) currentModInfosF.get(ModList.get()))) {
            modInfos.add((ModInfo) info);
        }

        for (ModContainer modContainer : ((List<ModContainer>) currentMods.get(ModList.get()))) {
            modContainers.add(modContainer);
        }

        modFiles.add((ModFile) modFile);
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

    private static void addToGameLayer(LoadingModList loadingModList) throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, UnexpectedException {
        for (ModFileInfo modFile : loadingModList.getModFiles()) {
            SecureJar secureJar = modFile.getFile().getSecureJar();
            String target = secureJar.name();

            Constructor<ResolvedModule> resolvedModuleConstructor = ResolvedModule.class.getDeclaredConstructor(Configuration.class,ModuleReference.class);
            resolvedModuleConstructor.setAccessible(true);

            Constructor<Module> moduleConstructor = Module.class.getDeclaredConstructor(ModuleLayer.class,ClassLoader.class, ModuleDescriptor.class, URI.class);
            moduleConstructor.setAccessible(true);

            JarModuleFinder finder = JarModuleFinder.of(secureJar);
            Set<ModuleReference> moduleReferences =  finder.findAll();

            ModuleClassLoader moduleClassLoader = getModuleClassLoader();

            Map<String,Module> namedModule = UnsafeUtils.getFieldValue(FMLLoader.getGameLayer(),"nameToModule",Map.class);
            Set<ResolvedModule> configurationModules = UnsafeUtils.getFieldValue(FMLLoader.getGameLayer().configuration(),"modules",Set.class);
            Map<String,ResolvedModule> configurationNameToModules = UnsafeUtils.getFieldValue(FMLLoader.getGameLayer().configuration(),"nameToModule",Map.class);

            Map<String, Object> resolvedRoots = UnsafeUtils.getFieldValue(moduleClassLoader,"resolvedRoots",Map.class);

            Set<Module> moduleSet = new HashSet<>();
            moduleSet.addAll(FMLLoader.getGameLayer().modules());

            HashSet<ResolvedModule> newConfigurationModules = new HashSet<>();
            newConfigurationModules.addAll(configurationModules);

            HashMap<String,ResolvedModule> newConfigurationNameToModules = new HashMap<>();
            newConfigurationNameToModules.putAll(configurationNameToModules);

            HashMap<String,Object> newResolvedRoots = new HashMap<>();
            newResolvedRoots.putAll(resolvedRoots);

            for (ModuleReference moduleReference : moduleReferences) {
                Module module = moduleConstructor.newInstance(FMLLoader.getGameLayer(),Injector.class.getClassLoader(),moduleReference.descriptor(),moduleReference.location().orElseThrow());
                ResolvedModule resolvedModule = resolvedModuleConstructor.newInstance(FMLLoader.getGameLayer().configuration(),moduleReference);

                moduleSet.add(module);
                namedModule.put(target,module);

                newConfigurationModules.add(resolvedModule);
                newConfigurationNameToModules.put(target,resolvedModule);

                newResolvedRoots.put(moduleReference.descriptor().name(),moduleReference);
            }

            UnsafeUtils.setFieldValue(FMLLoader.getGameLayer(),"modules",moduleSet);
            UnsafeUtils.setFieldValue(FMLLoader.getGameLayer(),"nameToModule",namedModule);

            UnsafeUtils.setFieldValue(FMLLoader.getGameLayer().configuration(),"modules",newConfigurationModules);
            UnsafeUtils.setFieldValue(FMLLoader.getGameLayer().configuration(),"nameToModule",newConfigurationNameToModules);

            UnsafeUtils.setFieldValue(moduleClassLoader,"resolvedRoots",newResolvedRoots);
        }
    }

    private static @NotNull ModuleClassLoader getModuleClassLoader() throws UnexpectedException {
        ClassLoader classLoader = GLFW.class.getClassLoader();
        ModuleClassLoader moduleClassLoader;
        if(!(classLoader instanceof ModuleClassLoader) || classLoader instanceof TransformingClassLoader){
            throw new RuntimeException("Failed to get the module class loader from GLFW.");
        }
        else if(classLoader instanceof ModuleClassLoader loader){
            moduleClassLoader = loader;
        }
        else{
            throw new UnexpectedException("How did you get there?");
        }
        return moduleClassLoader;
    }

    public static void setLayer(ModuleLayer layer,Configuration configuration,Module module,ResolvedModule resolvedModule){

    }
}

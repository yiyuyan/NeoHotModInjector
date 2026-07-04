package cn.ksmcbrigade.nhmj.utils;

import com.terraformersmc.mod_menu.ModMenu;
import com.terraformersmc.mod_menu.util.mod.neoforge.NeoforgeMod;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.ModuleLayerHandler;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import net.neoforged.fml.*;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.javafmlmod.FMLModContainer;
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
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class Injector {

    public static JarModsDotTomlModFileReader reader = new JarModsDotTomlModFileReader();

    public static void inject(Path path) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException, NoSuchFieldException, ClassNotFoundException {
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

        Method addLayerM = ModuleLayerHandler.class.getDeclaredMethod("addToLayer", IModuleLayerManager.Layer.class, SecureJar.class);
        addLayerM.setAccessible(true);
        IModuleLayerManager moduleLayerManager = Launcher.INSTANCE.findLayerManager().orElseThrow();

        if(moduleLayerManager instanceof ModuleLayerHandler handler){
            for (ModFileInfo file : loadingModList.getModFiles()) {
                addLayerM.invoke(handler, IModuleLayerManager.Layer.GAME,file.getFile().getSecureJar());
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
                .<ModContainer>mapMulti((a,b)->{
                    Objects.requireNonNull(b);
                    b.accept((ModContainer) a);
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
}

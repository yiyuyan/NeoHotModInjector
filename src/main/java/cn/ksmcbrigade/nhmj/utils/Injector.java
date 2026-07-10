package cn.ksmcbrigade.nhmj.utils;

import cn.ksmcbrigade.mr.utils.UnsafeUtils;
import cn.ksmcbrigade.mr.utils.mixin.*;
import cn.ksmcbrigade.nhmj.NHMJMod;
import cn.ksmcbrigade.nhmj.config.InjectorConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.terraformersmc.mod_menu.ModMenu;
import com.terraformersmc.mod_menu.util.mod.neoforge.NeoforgeMod;
import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.*;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.*;
import net.neoforged.fml.loading.*;
import net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.ModValidator;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.fml.loading.modscan.BackgroundScanHandler;
import net.neoforged.neoforge.client.*;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ClientTooltipComponentManager;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.map.RegisterMapDecorationRenderersEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;

import java.lang.instrument.ClassDefinition;
import java.lang.module.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.rmi.UnexpectedException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.ksmcbrigade.mr.utils.UnsafeUtils.getFieldValue;
import static cn.ksmcbrigade.mr.utils.UnsafeUtils.setFieldValue;
import static cn.ksmcbrigade.nhmj.NHMJMod.LOGGER;
import static net.neoforged.neoforge.client.DimensionSpecialEffectsManager.preRegisterVanillaEffects;
import static net.neoforged.neoforge.client.NamedRenderTypeManager.preRegisterVanillaRenderTypes;
import static net.neoforged.neoforge.client.RecipeBookManager.*;
import static net.neoforged.neoforge.client.gui.map.MapDecorationRendererManager.RENDERERS;
import static net.neoforged.neoforge.gametest.GameTestHooks.*;
import static net.neoforged.neoforge.resource.ResourcePackLoader.*;

@SuppressWarnings({"UnstableApiUsage", "unchecked", "rawtypes"})
public final class Injector {

    private static final Object TRANSFORM_LOCK = new Object();

    public static JarModsDotTomlModFileReader reader = new JarModsDotTomlModFileReader();

    public static final Class<?> mixinConfigClass;

    static {
        try {
            mixinConfigClass = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to get mixin config class.",e);
        }
    }

    public static void inject(Path path) throws Throwable {
        ToastComponent toastComponent = Minecraft.getInstance().getToasts();

        toastComponent.addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,Component.literal("Injecting..."),Component.literal(path.toFile().getName())));

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
            addIntoResManager(file.getFile());
        }

        if(InjectorConfig.MIXIN_TRANSFORM_MODE.get().equals(InjectorConfig.MixinTransformMode.NATIVE)){
            try {
                if(System.getProperty("java.vendor").contains("JetBrains") && !NHMJMod.jvmHooked){
                    LOGGER.info("Trying to enable AllowEnhancedClassRedefinition runtime.");
                    NHMJMod.enableAllowEnhancedClassRedefinition();
                }
                else if(!NHMJMod.jvmHooked){
                    throw new UnsupportedOperationException("The NATIVE mode is not support the jvm: "+System.getProperty("java.vendor"));
                }
            } catch (Throwable e) {
                LOGGER.error("Failed to enable NATIVE mode.",e);
                InjectorConfig.MIXIN_TRANSFORM_MODE.set(InjectorConfig.MixinTransformMode.TRAMPOLINE);
                if(Minecraft.getInstance().screen instanceof ConfigurationScreen configurationScreen){
                    Minecraft.getInstance().screen = new ConfigurationScreen(configurationScreen.mod,configurationScreen.lastScreen,configurationScreen.sectionScreen);
                    Minecraft.getInstance().getToasts().addToast(new SystemToast(SystemToast.SystemToastId.WORLD_ACCESS_FAILURE, Component.literal("Can't enable NATIVE mode!"),Component.literal("Please use JetBrainsRuntime 21.0.11+1-b1163.116.")));
                }
            }
        }

        try {

            Set<Class<?>> targetClasses = Collections.synchronizedSet(new HashSet<>());
            
            for (ModFileInfo file : loadingModList.getModFiles()) {
                for (String mixinConfig : file.getFile().getMixinConfigs()) {
                    Mixins.addConfiguration(mixinConfig);
                    IMixinConfig config = Mixins.getConfigs().stream().collect(Collectors.toMap(Config::getName, Config::getConfig)).get(mixinConfig);
                    if(config!=null){
                        config.decorate(FabricUtil.KEY_MOD_ID,file.getMods().getFirst().getModId());

                        MixinConfigUtils.onSelect(config);
                        setNotPrepared(config);
                        MixinConfigUtils.prepare(config,MixinAgentUtils.getFirstAgent());
                        MixinProcessorUtils.addIntoProcessor(MixinProcessorUtils.getProcessor(MixinAgentUtils.getFirstAgent()), config);

                        List<String> unhandledTargets = new ArrayList<>(MixinConfigUtils.getUnhandledTargets(config));
                        targetClasses.addAll(unhandledTargets.stream().map((s)->{
                            try {
                                return Class.forName(s);
                            } catch (ClassNotFoundException e) {
                                throw new RuntimeException(e);
                            }
                        }).toList());

                        Method postInitialiseM = mixinConfigClass.getDeclaredMethod("postInitialise", Extensions.class);
                        postInitialiseM.setAccessible(true);
                        postInitialiseM.invoke(config,(Extensions)MixinTransformerUtils.getTransformer().getExtensions());
                    }
                }
            }

            try {
                int poolSize = Math.max(1, InjectorConfig.MIXIN_TRANSFORM_POOLS.get());
                ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                ConcurrentLinkedQueue<Map.Entry<Class<?>, byte[]>> transformResults = new ConcurrentLinkedQueue<>();
                List<Future<?>> futures = new ArrayList<>();

                for (Class<?> targetClass : targetClasses) {
                    futures.add(executor.submit(() -> {
                        synchronized (TRANSFORM_LOCK) {
                            try {
                                byte[] bytes = MixinTransformerUtils.transform(targetClass);
                                transformResults.add(new AbstractMap.SimpleEntry<>(targetClass, bytes));
                            } catch (Throwable e) {
                                LOGGER.error("Failed to transform {}.", targetClass, e);
                            }
                        }
                    }));
                }

                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        LOGGER.error("Transform task failed.", e);
                    }
                }
                executor.shutdown();

                ModuleUtilsAccess.addAllReadsForFMLGameLayerModules();

                DeltaTracker.Timer timer0 = Minecraft.getInstance().timer;
                Minecraft.getInstance().noRender = true;
                Minecraft.getInstance().pause = true;
                Minecraft.getInstance().timer = new DeltaTracker.Timer(0, 0L, FloatUnaryOperator.identity());
                Minecraft.getInstance().timer.paused = true;

                try {
                    List<ClassDefinition> batchedDefs = new ArrayList<>();
                    boolean trampoline = InjectorConfig.MIXIN_TRANSFORM_MODE.get().equals(InjectorConfig.MixinTransformMode.TRAMPOLINE);

                    for (Map.Entry<Class<?>, byte[]> entry : transformResults) {
                        Class<?> targetClass = entry.getKey();
                        byte[] bytes = entry.getValue();
                        try {
                            if (trampoline) {
                                ClassDefinition def = MixinHotSwap.replaceMixedClasses(targetClass, bytes, MixinAgentUtils.getInst(), null);
                                if (def != null) batchedDefs.add(def);
                            } else {
                                batchedDefs.add(new ClassDefinition(targetClass, bytes));
                            }
                        } catch (Throwable e) {
                            LOGGER.error("Failed to redefine {}.", targetClass, e);
                        }
                    }

                    if (!batchedDefs.isEmpty()) {
                        Objects.requireNonNull(MixinAgentUtils.getInst()).redefineClasses(batchedDefs.toArray(new ClassDefinition[0]));
                    }
                } finally {
                    if (timer0.targetMsptProvider.equals(FloatUnaryOperator.identity()))
                        timer0 = new DeltaTracker.Timer(20.0F, 0L, Minecraft.getInstance()::getTickTargetMillis);
                    Minecraft.getInstance().timer = timer0;
                    Minecraft.getInstance().noRender = false;
                    Minecraft.getInstance().pause = false;
                }

                ModuleUtilsAccess.addAllReadsForFMLGameLayerModules();
            } catch (Throwable e) {
                LOGGER.error("Failed to reapply mixin configs.", e);
            }
        } catch (Throwable e) {
            LOGGER.error("Failed to add or reapply mixins for {}.",path.toFile().getName(),e);
            throw new Throwable("Error in inject mixins.",e);
        }

        ModuleUtilsAccess.addAllReadsForFMLGameLayerModules();

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

        ModuleUtilsAccess.addAllReadsForFMLGameLayerModules();

        DeferredWorkQueue workQueue = new DeferredWorkQueue("Mod Construction");
        for (ModContainer modContainer : containerList){
            ModLoadingContext.get().setActiveContainer(modContainer);
            constructModM.invoke(modContainer);
            modContainer.acceptEvent(new FMLConstructModEvent(modContainer, workQueue));
            addIntoBTL(modContainer);
            ModLoadingContext.get().setActiveContainer(null);
        }

        if (FMLEnvironment.dist == Dist.CLIENT) {
            loadConfigs(ModConfig.Type.CLIENT, FMLPaths.CONFIGDIR.get());
        }
        loadConfigs(ModConfig.Type.COMMON, FMLPaths.CONFIGDIR.get());

        for (ModContainer modContainer : containerList) {
            modContainer.acceptEvent(new FMLCommonSetupEvent(modContainer,new DeferredWorkQueue("Common setup")));
            modContainer.acceptEvent(new FMLClientSetupEvent(modContainer,new DeferredWorkQueue("Sided setup")));

            modContainer.acceptEvent(new InterModEnqueueEvent(modContainer,new DeferredWorkQueue("Enqueue IMC")));
            modContainer.acceptEvent(new InterModProcessEvent(modContainer,new DeferredWorkQueue("Process IMC")));

            modContainer.acceptEvent(new FMLLoadCompleteEvent(modContainer,new DeferredWorkQueue("Complete loading of %d mods".formatted(ModList.get().size()))));

            modContainer.acceptEvent(new RegisterPayloadHandlersEvent());

            modContainer.acceptEvent(new RegisterParticleProvidersEvent(Minecraft.getInstance().particleEngine));
            modContainer.acceptEvent(new RenderLevelStageEvent.RegisterStageEvent());

            modContainer.acceptEvent(new RegisterClientExtensionsEvent());
            registerGametests(modContainer);
            modContainer.acceptEvent(new RegisterSpriteSourceTypesEvent(ClientHooks.makeSpriteSourceTypesMap()));
            modContainer.acceptEvent(new RegisterMenuScreensEvent(MenuScreens.SCREENS));

            modContainer.acceptEvent(new RegisterClientReloadListenersEvent(Minecraft.getInstance().resourceManager));
            modContainer.acceptEvent(new EntityRenderersEvent.RegisterLayerDefinitions());
            modContainer.acceptEvent(new EntityRenderersEvent.RegisterRenderers());

            HashMap<Class<? extends TooltipComponent>, Function<TooltipComponent, ClientTooltipComponent>> factories = new HashMap<>(ClientTooltipComponentManager.FACTORIES);
            modContainer.acceptEvent(new RegisterClientTooltipComponentFactoriesEvent(factories));
            ClientTooltipComponentManager.FACTORIES = ImmutableMap.copyOf(factories);

            HashMap<EntityType<?>, ResourceLocation> shaders = new HashMap<>(EntitySpectatorShaderManager.SHADERS);
            RegisterEntitySpectatorShadersEvent event = new RegisterEntitySpectatorShadersEvent(shaders);
            modContainer.acceptEvent(event);
            EntitySpectatorShaderManager.SHADERS = ImmutableMap.copyOf(shaders);

            modContainer.acceptEvent(new RegisterKeyMappingsEvent(Minecraft.getInstance().options));

            HashMap<RecipeBookCategories, ImmutableList<RecipeBookCategories>> aggregateCategories = new HashMap(ImmutableMap.of(RecipeBookCategories.CRAFTING_SEARCH, ImmutableList.of(RecipeBookCategories.CRAFTING_EQUIPMENT, RecipeBookCategories.CRAFTING_BUILDING_BLOCKS, RecipeBookCategories.CRAFTING_MISC, RecipeBookCategories.CRAFTING_REDSTONE), RecipeBookCategories.FURNACE_SEARCH, ImmutableList.of(RecipeBookCategories.FURNACE_FOOD, RecipeBookCategories.FURNACE_BLOCKS, RecipeBookCategories.FURNACE_MISC), RecipeBookCategories.BLAST_FURNACE_SEARCH, ImmutableList.of(RecipeBookCategories.BLAST_FURNACE_BLOCKS, RecipeBookCategories.BLAST_FURNACE_MISC), RecipeBookCategories.SMOKER_SEARCH, ImmutableList.of(RecipeBookCategories.SMOKER_FOOD)));
            HashMap<RecipeBookType, ImmutableList<RecipeBookCategories>> typeCategories = new HashMap();
            HashMap<RecipeType<?>, Function<RecipeHolder<?>, RecipeBookCategories>> recipeCategoryLookups = new HashMap();
            RegisterRecipeBookCategoriesEvent event2 = new RegisterRecipeBookCategoriesEvent(aggregateCategories, typeCategories, recipeCategoryLookups);
            modContainer.acceptEvent(event2);
            AGGREGATE_CATEGORIES.putAll(aggregateCategories);
            TYPE_CATEGORIES.putAll(typeCategories);
            RECIPE_CATEGORY_LOOKUPS.putAll(recipeCategoryLookups);

            HashMap<ResourceLocation, DimensionSpecialEffects> effects = new HashMap();
            DimensionSpecialEffectsManager.DEFAULT_EFFECTS = preRegisterVanillaEffects(effects);
            HashMap<ResourceLocation, DimensionSpecialEffects> effectsD = new HashMap(effects);
            effectsD.putAll(DimensionSpecialEffectsManager.EFFECTS);
            modContainer.acceptEvent(new RegisterDimensionSpecialEffectsEvent(effectsD));
            DimensionSpecialEffectsManager.EFFECTS = ImmutableMap.copyOf(effectsD);

            HashMap<ResourceLocation, RenderTypeGroup> renderTypes = new HashMap();
            preRegisterVanillaRenderTypes(renderTypes);
            HashMap<ResourceLocation, RenderTypeGroup> renderTypesD = new HashMap(renderTypes);
            renderTypesD.putAll(renderTypes);
            modContainer.acceptEvent(new RegisterNamedRenderTypesEvent(renderTypesD));
            NamedRenderTypeManager.RENDER_TYPES = ImmutableMap.copyOf(renderTypesD);

            ImmutableList.Builder<ColorResolver> builder = ImmutableList.builder();
            builder.addAll(ColorResolverManager.colorResolvers.stream().toList());
            modContainer.acceptEvent(new RegisterColorHandlersEvent.ColorResolvers(builder));
            ColorResolverManager.colorResolvers = builder.build();

            Map<ResourceKey<WorldPreset>, PresetEditor> gatheredEditors = new HashMap(PresetEditorManager.editors);
            PresetEditor.EDITORS.forEach((k, v) -> k.ifPresent((key) -> gatheredEditors.put(key, v)));
            modContainer.acceptEvent(new RegisterPresetEditorsEvent(gatheredEditors));
            PresetEditorManager.editors = gatheredEditors;

            ModLoader.postEvent(new RegisterMapDecorationRenderersEvent(RENDERERS));

            modContainer.acceptEvent(new SoundEngineLoadEvent(Minecraft.getInstance().soundManager.soundEngine));
            modContainer.acceptEvent(new RegisterRenderBuffersEvent(Minecraft.getInstance().renderBuffers.bufferSource.fixedBuffers));
        }

        NHMJMod.injectorReload = true;

        toastComponent.addToast(new SystemToast(SystemToast.SystemToastId.PERIODIC_NOTIFICATION,Component.literal("Inject successfully!"),Component.literal(path.toFile().getName())));
    }

    public static void registerGametests(ModContainer modContainer) {
        if (isGametestEnabled()) {
            Set<String> enabledNamespaces = GameTestHooks.getEnabledNamespaces();
            LOGGER.info("Enabled Gametest Namespaces: {}", enabledNamespaces);
            Set<Method> gameTestMethods = new HashSet();
            RegisterGameTestsEvent event = new RegisterGameTestsEvent(gameTestMethods);
            modContainer.acceptEvent(event);
            ModList.get().getAllScanData().stream().map(ModFileScanData::getAnnotations).flatMap(Collection::stream).filter((a) -> GAME_TEST_HOLDER.equals(a.annotationType())).forEach((a) -> addGameTestMethods(a, gameTestMethods));

            for(Method gameTestMethod : gameTestMethods) {
                GameTestRegistry.register(gameTestMethod, enabledNamespaces);
            }

        }

    }

    private static void setNotPrepared(IMixinConfig config) throws IllegalAccessException, NoSuchFieldException {
        Field field = config.getClass().getDeclaredField("prepared");
        field.setAccessible(true);
        field.set(config,false);
    }

    private static void loadConfigs(ModConfig.Type type,Path path){
        try {
            Logger LOGGER = UnsafeUtils.getFieldValue(ConfigTracker.class,"LOGGER", Logger.class);
            Marker CONFIG = UnsafeUtils.getFieldValue(ConfigTracker.class,"CONFIG",Marker.class);
            EnumMap<ModConfig.Type, Set<ModConfig>> configSets = UnsafeUtils.getFieldValue(ConfigTracker.INSTANCE,"configSets", EnumMap.class);

            Method openConfigM = ConfigTracker.class.getDeclaredMethod("openConfig", ModConfig.class, Path.class, Path.class);
            openConfigM.setAccessible(true);

            if(LOGGER==null) LOGGER = NHMJMod.LOGGER;
            if(CONFIG==null) CONFIG = MarkerFactory.getMarker("CONFIG");
            if(configSets==null) configSets = new EnumMap<>(ModConfig.Type.class);

            LOGGER.debug(CONFIG, "Loading configs type {}", type);
            configSets.get(type).stream().filter(modConfig -> modConfig.getLoadedConfig() == null).forEach(config -> {
                try {
                    openConfigM.invoke(ConfigTracker.INSTANCE,config, path, null);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Throwable e) {
            LOGGER.error("Failed to load configs. {}:{}",e.getClass(),e.getMessage());
        }
    }

    private static void addIntoResManager(IModFile modFile) {
        Minecraft mc = Minecraft.getInstance();

        modResourcePacks.put(modFile,ResourcePackLoader.createPackForMod(modFile.getModFileInfo()));

        ResourcePackLoader.populatePackRepository(mc.resourcePackRepository, PackType.CLIENT_RESOURCES, false);
        mc.resourcePackRepository.reload();
    }

    private static void addIntoBTL(ModContainer modContainer) {
        try {
            ModMenu.MODS.put(modContainer.getModId(),new NeoforgeMod(modContainer));
            Class<?> clazz = Class.forName("com.terraformersmc.mod_menu.ModMenu");
            Class<?> neoforgeMod = Class.forName("com.terraformersmc.mod_menu.util.mod.neoforge.NeoforgeMod");
            Map<String,Object> MODS = UnsafeUtils.getFieldValue(clazz,"MODS",Map.class); //mem address
            Constructor<?> modConstructor = neoforgeMod.getDeclaredConstructor(ModContainer.class);
            if(MODS==null){
                MODS = new HashMap<>();
                LOGGER.warn("The MODS in ModMenu is null.");
            }

            MODS.put(modContainer.getModId(),modConstructor.newInstance(modContainer));
        }
        catch (Throwable throwable){
            if(throwable instanceof ClassNotFoundException || throwable instanceof NoClassDefFoundError) return;
            LOGGER.warn("Can't add {} into the BetterModList,did you install it? {}:{}",modContainer.getModId(),throwable.getClass(),throwable.getMessage());
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
                LOGGER.warn("The resolvedRoots is null.");
                for (ModuleReference reference : FMLLoader.getGameLayer().configuration().modules().stream().map(ResolvedModule::reference).toList()) {
                    newResolvedRoots.put(reference.descriptor().name(),reference);
                }
            }

            HashMap<String,Object> newPackageLookup = new HashMap<>();
            if(packageLookup!=null){
                newPackageLookup.putAll(packageLookup);
            }
            else{
                LOGGER.warn("The packageLookup is null.");
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
            LOGGER.error("Failed to get filed value by unsafe.",var4);
            return null;
        }
    }

    public static void setFieldValueWithTargetClass(Class<?> targetClass,Object target, String fieldName, Object value) {
        try {
            setFieldValue(targetClass.getDeclaredField(fieldName), target, value);
        } catch (Throwable var4) {
            LOGGER.error("Failed to set filed value by unsafe.",var4);
        }

    }
}

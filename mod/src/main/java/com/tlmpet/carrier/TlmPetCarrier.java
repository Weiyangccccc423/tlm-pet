package com.tlmpet.carrier;

import com.tlmpet.carrier.init.InitItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 模组主类。
 * <p>
 * 与 TLM 的全部交互都通过 {@code com.tlmpet.carrier.compat.TlmCompat} 走 {@code @LittleMaidExtension}
 * 扩展点完成，本类不做任何对 TLM 类的直接引用 —— 这样即使 TLM 缺失，本类仍能正常加载并给出清晰报错。
 * <p>
 * 用 {@code LogManager} 而不是 Forge 的 {@code LogUtils}：这样纯逻辑的单元测试
 * 不必启动 Forge 就能拿到 logger。
 */
@Mod(TlmPetCarrier.MOD_ID)
public class TlmPetCarrier {
    public static final String MOD_ID = "tlm_pet";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public TlmPetCarrier() {
        // 用 FMLJavaModLoadingContext.get() 而不是把 context 作为构造参数：后者能编过，
        // 但在 1.20.1 的 FML 里是否真的支持构造参数注入无法静态确认；若不支持就是运行时
        // 构造失败（模组整个加载不了），代价远大于这里一条弃用告警。
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        InitItems.init(modEventBus);
        LOGGER.info("Touhou Little Maid: Bond 正在初始化");
    }
}

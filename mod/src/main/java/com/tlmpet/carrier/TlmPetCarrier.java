package com.tlmpet.carrier;

import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 模组主类。
 * <p>
 * 与 TLM 的全部交互都通过 {@code com.tlmpet.carrier.compat.TlmCompat} 走 {@code @LittleMaidExtension}
 * 扩展点完成，本类不做任何对 TLM 类的直接引用 —— 这样即使 TLM 缺失，本类仍能正常加载并给出清晰报错。
 */
@Mod(TlmPetCarrier.MOD_ID)
public class TlmPetCarrier {
    public static final String MOD_ID = "tlm_pet";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public TlmPetCarrier() {
        LOGGER.info("Touhou Little Maid: Bond 正在初始化");
    }
}

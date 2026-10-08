package com.tlmpet.carrier.compat;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.tlmpet.carrier.TlmPetCarrier;

/**
 * TLM 扩展入口。
 * <p>
 * TLM 通过 {@code AnnotatedInstanceUtil} 扫描 {@code ModList.getAllScanData()} 自动发现本类，
 * 因此无需手动注册。注解的另一个作用是：若未安装 TLM，本类不会被加载，从而保证模组仍能启动。
 * <p>
 * {@code ILittleMaid} 的全部方法都是 {@code default}，此处按需覆写即可。
 */
@LittleMaidExtension
public class TlmCompat implements ILittleMaid {
    public TlmCompat() {
        TlmPetCarrier.LOGGER.info("已挂载到 Touhou Little Maid 的扩展点");
    }
}

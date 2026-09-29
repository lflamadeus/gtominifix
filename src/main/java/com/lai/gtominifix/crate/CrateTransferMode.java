package com.lai.gtominifix.crate;

/**
 * 板条箱界面上补回来的两种批量转移操作。
 *
 * <p>它们对应「普通箱子里能用的那两个快捷键」（来自 Inventory Essentials）：
 * 在 GT / GTO 的板条箱界面上，这两个操作因为界面走 LDLib 的 {@code ModularUI} 而失效。
 */
public enum CrateTransferMode {

    /**
     * Ctrl + Shift + 左键：把<b>与鼠标下这一格同类</b>的物品全部转移。
     *
     * <p>源侧由鼠标下那一格决定：点箱子里的物品就往背包搬，点背包里的物品就往箱子搬。
     * 目标侧装不下时剩下的留在原处 —— 也就是「直到填满背包」。
     */
    BY_TYPE,

    /**
     * 空格 + 左键：把鼠标下<b>那一侧</b>的东西整侧转移。
     *
     * <p>鼠标停在背包上 → 把背包的东西搬进容器，且<b>只搬主背包的 27 格</b>，
     * 快捷栏那 9 格不动（这是原快捷键的语义：快捷栏是「常用物品区」，不该被一键清空）。
     * 鼠标停在容器上 → 把容器里的东西搬进背包。
     */
    ALL;

    /** 网络包里用 ordinal 当作线路标识，改动本枚举的顺序要同步提升协议版本。 */
    public static CrateTransferMode fromId(int id) {
        CrateTransferMode[] values = values();
        return id >= 0 && id < values.length ? values[id] : null;
    }
}

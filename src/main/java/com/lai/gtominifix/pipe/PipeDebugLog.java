package com.lai.gtominifix.pipe;

import com.lai.gtominifix.GtoMiniFixConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 本模组自己的调试日志文件（{@code logs/gtominifix-debug.log}）。
 *
 * <h2>为什么不用 log4j</h2>
 * 实测本模组的 SLF4J 输出在某些实例里一个字都落不了盘 —— 连 {@code logs/debug.log}
 * 里都没有本模组的行，而同一次启动里 Forge / Mixin 的日志都在。没有可用的日志，
 * 「跑一段时间才坏」这类问题就只能靠猜。所以这里用最笨也最可靠的办法：
 * 自己 {@link Files#write} 追加写文件，完全绕开日志框架。
 *
 * <p>写法是「谁调谁同步」：每条一行、写完立即 flush，量很小（只有探针结果、
 * 逐秒观察、以及少数关键事件），不会拖慢游戏。所有 I/O 都吞异常：
 * 出错就把自己永久关掉，绝不影响游戏。
 */
public final class PipeDebugLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 超过这个大小就轮转成 {@code .1}，避免玩家玩几个月后攒出一个巨大的文件。 */
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private static final Object LOCK = new Object();

    private static volatile Path file;
    private static volatile boolean broken;
    private static volatile String brokenReason = "";

    private PipeDebugLog() {}

    /** 在模组构造器里调一次：定位游戏目录下的 {@code logs/}。 */
    public static void init(Path gameDir) {
        try {
            Path dir = gameDir.resolve("logs");
            Files.createDirectories(dir);
            Path f = dir.resolve("gtominifix-debug.log");
            if (Files.exists(f) && Files.size(f) > MAX_BYTES) {
                Path old = dir.resolve("gtominifix-debug.log.1");
                Files.deleteIfExists(old);
                Files.move(f, old);
            }
            file = f;
            log("===== gtominifix 调试日志（本次启动）=====");
        } catch (Throwable t) {
            broken = true;
            brokenReason = t.getClass().getSimpleName();
        }
    }

    /** 供 {@code /gtominifix config} 与探针显示。 */
    public static String pathText() {
        Path f = file;
        if (broken) {
            return "不可用（" + brokenReason + "）";
        }
        return f == null ? "未初始化" : f.toString();
    }

    public static boolean isBroken() {
        return broken;
    }

    /** 写一行（自动补时间戳）。关掉配置项或文件不可用时什么都不做。 */
    public static void log(String message) {
        Path f = file;
        if (f == null || broken || !GtoMiniFixConfig.pipeDebugFile) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(TS) + "] " + message;
        synchronized (LOCK) {
            try {
                Files.write(f, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable t) {
                broken = true;
                brokenReason = t.getClass().getSimpleName();
            }
        }
    }

    /** 写多行（探针结果）。 */
    public static void logLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append(System.lineSeparator());
        }
        log(sb.toString().stripTrailing());
    }
}

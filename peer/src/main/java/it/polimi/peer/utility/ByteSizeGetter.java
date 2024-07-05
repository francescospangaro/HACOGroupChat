package it.polimi.peer.utility;

import java.lang.instrument.Instrumentation;

public class ByteSizeGetter {
    private static Instrumentation instrumentation;

    public static void premain(final String agentArgs, final Instrumentation inst) {
        instrumentation = inst;
    }

    // Cast to int, we only need a number in the correct ballpark
    public static int getByteSize(Object o) {
        return (int) instrumentation.getObjectSize(o);
    }
}

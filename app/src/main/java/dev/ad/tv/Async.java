package dev.ad.tv;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Async {
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {}

    static void io(Runnable task) {
        IO.execute(task);
    }

    static void main(Runnable task) {
        MAIN.post(task);
    }
}

/**
 * Original work: copyright 2009-2015 Lukáš Křečan 
 * https://github.com/lukas-krecan/completion-stage
 * 
 * This class is based on the work create by Lukáš Křečan 
 * under the Apache License, Version 2.0. Please see 
 * https://github.com/lukas-krecan/completion-stage/blob/completion-stage-0.0.9/src/main/java/net/javacrumbs/completionstage/SimpleCompletionStage.java
 * 
 * Modified work: copyright 2015-2017 Valery Silaev (http://vsilaev.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.tascalate.concurrent;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Base superclass for both root and intermediate {@link Promise}-s that
 * represent blocking long-running tasks
 * 
 * @author vsilaev
 *
 * @param <T>
 *   a type of the successfully executed task result   
 */
abstract class AbstractCompletableTask<T> extends PromiseAdapter<T> implements Promise<T> {

    private final CallbackRegistry<T> callbackRegistry = new CallbackRegistry<>();
    protected final RunnableFuture<T> task;
    protected final Callable<T> action;
    // Recursive cancellation support
    private CompletionStage<?>[] dependencies = null;
    private boolean dependenciesMightHaveBeenInterrupted = false;
    private final Object dependenciesLock = new Object();

    protected AbstractCompletableTask(Executor defaultExecutor, Callable<T> action, CompletionStage<?> dependency) {
        super(defaultExecutor);
        this.action = action;
        this.task = new StageTransition(action);
        if (dependency != null) {
            this.dependencies = new CompletionStage<?>[] { dependency };
        }
    }

    abstract Runnable setupTransition(Callable<T> code);

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (task.cancel(mayInterruptIfRunning)) {
            // Cancel dependencies if any, synchronization is needed due to
            // possible async execution of fn in thenComposeAsync
            synchronized (dependenciesLock) {
                if (dependencies != null) {
                    Arrays.stream(dependencies).forEach(d -> {
                        CompletablePromise.cancelPromise(d, mayInterruptIfRunning);
                    });
                    // Keep value of mayInterruptIfRunning for the case when
                    // a composite task is being executed during cancellation
                    dependenciesMightHaveBeenInterrupted = mayInterruptIfRunning;
                }
            }
            onError(new CancellationException());
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }

    @Override
    public boolean isDone() {
        return task.isDone();
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        try {
            return task.get();
        } catch (ExecutionException ex) {
            throw rewrapExecutionException(ex);
        }
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return task.get(timeout, unit);
        } catch (ExecutionException ex) {
            throw rewrapExecutionException(ex);
        }
    }

    boolean onSuccess(T result) {
        return callbackRegistry.success(result);
    }

    boolean onError(Throwable ex) {
        return callbackRegistry.failure(ex);
    }

    class StageTransition extends FutureTask<T> {
        StageTransition(Callable<T> callable) {
            super(callable);
        }

        @Override
        protected void set(T v) {
            super.set(v);
            if (!isCancelled()) {
                onSuccess(v);
            } else {
                onError(new CancellationException());
            }
        };

        @Override
        protected void setException(Throwable t) {
            super.setException(t);
            onError(t);
        };
    }

    @Override
    public <U> Promise<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        AbstractCompletableTask<U> nextStage = internalCreateCompletionStage(executor);
        addCallbacks(nextStage, fn, executor);
        return nextStage;
    }

    @Override
    public Promise<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return thenApplyAsync(consumerAsFunction(action), executor);
    }

    @Override
    public Promise<Void> thenRunAsync(Runnable action, Executor executor) {
        return thenApplyAsync(runnableAsFunction(action), executor);
    }

    @Override
    public <U, V> Promise<V> thenCombineAsync(CompletionStage<? extends U> other,
                                              BiFunction<? super T, ? super U, ? extends V> fn, 
                                              Executor executor) {

        AbstractCompletableTask<V> nextStage = doThenComposeAsync(
                result1 -> other.thenApplyAsync(result2 -> fn.apply(result1, result2), executor),
                SAME_THREAD_EXECUTOR);
        
        nextStage.whenComplete((r, e) -> {
            if (nextStage.isCancelled()) {
                CompletablePromise.cancelPromise(other, nextStage.dependenciesMightHaveBeenInterrupted);
            }
        });
        
        return nextStage;
    }

    @Override
    public <U> Promise<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
                                                 BiConsumer<? super T, ? super U> action, 
                                                 Executor executor) {
        return thenCombineAsync(other,
                // transform BiConsumer to BiFunction
                (t, u) -> {
                    action.accept(t, u);
                    return null;
                }, executor);
    }

    @Override
    public Promise<Void> runAfterBothAsync(CompletionStage<?> other, 
                                           Runnable action, 
                                           Executor executor) {
        return thenCombineAsync(other,
                // transform Runnable to BiFunction
                (t, r) -> {
                    action.run();
                    return null;
                }, executor);
    }

    @Override
    public <U> Promise<U> applyToEitherAsync(CompletionStage<? extends T> other, 
                                             Function<? super T, U> fn,
                                             Executor executor) {

        return doApplyToEitherAsync(this, other, fn, executor);
    }

    @Override
    public Promise<Void> acceptEitherAsync(CompletionStage<? extends T> other,
                                           Consumer<? super T> action,
                                           Executor executor) {

        return applyToEitherAsync(other, consumerAsFunction(action), executor);
    }

    @Override
    public Promise<Void> runAfterEitherAsync(CompletionStage<?> other, 
                                             Runnable action, 
                                             Executor executor) {
        
        return doApplyToEitherAsync(this, other, runnableAsFunction(action), executor);
    }

    @Override
    public <U> Promise<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        return doThenComposeAsync(fn, executor);
    }

    @Override
    public Promise<T> exceptionally(Function<Throwable, ? extends T> fn) {
        AbstractCompletableTask<T> nextStage = internalCreateCompletionStage(getDefaultExecutor());
        addCallbacks(nextStage, Function.identity(), fn, SAME_THREAD_EXECUTOR);
        return nextStage;
    }

    @Override
    public Promise<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        AbstractCompletableTask<T> nextStage = internalCreateCompletionStage(executor);
        addCallbacks(
            nextStage, 
            result -> {
                action.accept(result, null);
                return result;
            }, 
            failure -> {
                try {
                    action.accept(null, failure);
                    return forwardException(failure);
                } catch (Throwable e) {
                    return forwardException(e);
                }
            }, 
            executor
        );
        return nextStage;
    }

    @Override
    public <U> Promise<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        AbstractCompletableTask<U> nextStage = internalCreateCompletionStage(executor);
        addCallbacks(
            nextStage, 
            result -> fn.apply(result, null),
            // exceptions are treated as success
            error -> fn.apply(null, error), executor
        );
        return nextStage;
    }

    @Override
    public CompletableFuture<T> toCompletableFuture() {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        // nextStage is CompletableFuture rather than AbstractCompletableTask
        // so trigger completion on ad-hoc runnable rather than on
        // nextStage.task
        Function<Callable<T>, Runnable> setup = c -> () -> {
            try {
                c.call();
            } catch (Throwable ex) {
                completableFuture.completeExceptionally(ex);
            }
        };
        addCallbacks(
            setup, 
            consumerAsFunction(completableFuture::complete),
            consumerAsFunction(completableFuture::completeExceptionally), 
            SAME_THREAD_EXECUTOR
        );
        return completableFuture;
    }

    abstract protected <U> AbstractCompletableTask<U> createCompletionStage(Executor executor);

    /**
     * This method exists just to reconcile generics when called from
     * {@link #runAfterEitherAsync} which has unexpected type of parameter
     * "other". The alternative is to ignore compiler warning.
     */
    private <R, U> Promise<U> doApplyToEitherAsync(CompletionStage<? extends R> first,
                                                   CompletionStage<? extends R> second, 
                                                   Function<? super R, U> fn, 
                                                   Executor executor) {

        AbstractCompletableTask<R> nextStage = internalCreateCompletionStage(executor);

        // Next stage is not exposed to the client, so we can
        // short-circuit its initiation - just fire callbacks
        // without task execution (unlike as in other methods,
        // event in thenComposeAsync with its ad-hoc execution)

        // In certain sense, nextStage here is bogus: neither
        // of Future-defined methods are functional.
        BiConsumer<R, Throwable> action = (result, failure) -> {
            if (failure == null) {
                nextStage.onSuccess(result);
            } else {
                nextStage.onError(wrapException(failure));
            }
        };
        // only the first result is accepted by completion stage,
        // the other one is ignored
        first.whenComplete(action);
        second.whenComplete(action);
        
        // Next stage is not exposed to the client, no need to synchronize
        nextStage.dependencies = new CompletionStage<?>[] { this, first, second };

        return nextStage.thenApplyAsync(fn, executor);
    }

    private <U> AbstractCompletableTask<U> doThenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        
        AbstractCompletableTask<U> nextStage = internalCreateCompletionStage(executor);
        AbstractCompletableTask<Void> tempStage = internalCreateCompletionStage(executor);

        // We must ALWAYS run through the execution
        // of nextStage.task when this nextStage is
        // exposed to the client, even in a "trivial" case:
        // Success path, just return value
        // Failure path, just re-throw exception
        Executor helperExecutor = SAME_THREAD_EXECUTOR;
        BiConsumer<? super U, ? super Throwable> moveToNextStage = (r, e) -> {
            if (null == e)
                runDirectly(nextStage, Function.identity(), r, helperExecutor);
            else
                runDirectly(nextStage, AbstractCompletableTask::forwardException, e, helperExecutor);
        };

        // Important -- tempStage is the target here
        addCallbacks(
            tempStage, 
            consumerAsFunction(r -> {
                // Apply might take LONG time to run
                CompletionStage<?> cs = fn.apply(r).whenComplete(moveToNextStage);
                // Next stage is exposed to the client, synchronization needed
                synchronized (nextStage.dependenciesLock) {
                    if (!nextStage.isCancelled()) {
                        nextStage.dependencies = new CompletionStage<?>[] { cs };
                    } else {
                        // Next stage might have been cancelled before fn.apply(r) finished
                        CompletablePromise.cancelPromise(cs, nextStage.dependenciesMightHaveBeenInterrupted);
                    }
                }
            }), 
            e -> { moveToNextStage.accept(null, e); return null; }, /* must-have if fn.apply above failed */
            executor
        );

        return nextStage;
    }
    
    private <U> AbstractCompletableTask<U> internalCreateCompletionStage(Executor executor) {
        // Preserve default async executor, or use user-supplied executor as default
        // But don't let SAME_THREAD_EXECUTOR to be a default async executor
        return createCompletionStage(executor == SAME_THREAD_EXECUTOR ? getDefaultExecutor() : executor);
    }

    private static <V, R> Function<V, R> consumerAsFunction(Consumer<? super V> action) {
        return result -> {
            action.accept(result);
            return null;
        };
    }

    private static <R> Function<R, Void> runnableAsFunction(Runnable action) {
        return result -> {
            action.run();
            return null;
        };
    }

    private static <U> U forwardException(Throwable e) {
        throw wrapException(e);
    }

    private static CompletionException wrapException(Throwable e) {
        if (e instanceof CompletionException) {
            return (CompletionException) e;
        } else {
            return new CompletionException(e);
        }
    }
    
    private static ExecutionException rewrapExecutionException(ExecutionException ex) {
        if (ex.getCause() instanceof CompletionException) {
            Throwable completionExceptionReason = ex.getCause().getCause();
            if (null != completionExceptionReason) {
                return new ExecutionException(ex.getMessage(), completionExceptionReason);
            }
        }
        return ex;
    }    

    private <U> void addCallbacks(AbstractCompletableTask<U> targetStage,
                                  Function<? super T, ? extends U> successCallback, 
                                  Executor executor) {
        
        addCallbacks(targetStage, successCallback, AbstractCompletableTask::forwardException, executor);
    }

    private <U> void addCallbacks(AbstractCompletableTask<U> targetStage,
                                  Function<? super T, ? extends U> successCallback, 
                                  Function<Throwable, ? extends U> failureCallback,
                                  Executor executor) {
        
        addCallbacks(targetStage::setupTransition, successCallback, failureCallback, executor);
    }

    private <U> void addCallbacks(Function<? super Callable<U>, ? extends Runnable> targetSetup,
                                  Function<? super T, ? extends U> successCallback, 
                                  Function<Throwable, ? extends U> failureCallback,
                                  Executor executor) {
        
        callbackRegistry.addCallbacks(targetSetup, successCallback, failureCallback, executor);
    }

    private static <S, U> void runDirectly(AbstractCompletableTask<U> targetStage,
                                           Function<? super S, ? extends U> callback, 
                                           S value, 
                                           Executor executor) {

        CallbackRegistry.callCallback(targetStage::setupTransition, callback, value, executor);
    }
}
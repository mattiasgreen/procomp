package procomp.core.common;

import java.util.Objects;
import java.util.function.Function;

/**
 * Explicit functional result type representing either a success value ({@link Ok})
 * or a typed failure ({@link Err}).
 */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "Result.Ok value must not be null");
        }
    }

    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "Result.Err error must not be null");
        }
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok<T, E>;
    }

    default boolean isErr() {
        return this instanceof Err<T, E>;
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> fn) {
        Objects.requireNonNull(fn, "fn must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> ok(fn.apply(value));
            case Err<T, E>(E error) -> err(error);
        };
    }

    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<U, E>> fn) {
        Objects.requireNonNull(fn, "fn must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> Objects.requireNonNull(fn.apply(value), "flatMap returned null");
            case Err<T, E>(E error) -> err(error);
        };
    }

    default <F> Result<T, F> mapError(Function<? super E, ? extends F> fn) {
        Objects.requireNonNull(fn, "fn must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> ok(value);
            case Err<T, E>(E error) -> err(fn.apply(error));
        };
    }

    default <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
        Objects.requireNonNull(onOk, "onOk must not be null");
        Objects.requireNonNull(onErr, "onErr must not be null");
        return switch (this) {
            case Ok<T, E>(T value) -> onOk.apply(value);
            case Err<T, E>(E error) -> onErr.apply(error);
        };
    }

    default <U> Result<U, E> castError() {
        return switch (this) {
            case Ok<T, E> _ -> throw new IllegalStateException("castError() called on Ok result");
            case Err<T, E>(E error) -> err(error);
        };
    }
}

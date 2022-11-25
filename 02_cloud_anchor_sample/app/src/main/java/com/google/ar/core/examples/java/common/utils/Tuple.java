package com.google.ar.core.examples.java.common.utils;

/**
 * Tuple Class
 *  - Example)
 *   1) new Tuple<new Object(), new Object()>();
 *   2) Tuple.of(new Object(), new Object())
 *
 * @author akageun
 */
public class Tuple<A, B> {

    private final A a;
    private final B b;

    public Tuple(A a, B b) {
        this.a = a;
        this.b = b;
    }

    public static <A, B> Tuple<A, B> of(final A a, final B b) {
        return new Tuple<>(a, b);
    }

    public A getA() {
        return a;
    }

    public B getB() {
        return b;
    }
}
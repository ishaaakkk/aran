package edu.upc.utils;

import java.util.ArrayList;

public class BetaDistribution {

    private static double yConst = 0.57721566490153286060651209008240243104215933593992;
    private static ArrayList<Double> y1mem = new ArrayList<>() {
        {
            add(-yConst);
        }
    };
    private static ArrayList<Double> y2mem = new ArrayList<>() {
        {
            add(Math.pow(Math.PI, 2) / 6);
        }
    };
    private static ArrayList<Double> y3mem = new ArrayList<>() {
        {
            add(-2.404114);
        }
    };

    public static double y1(int n) {
        if (n >= 1000)
            return Math.log(n);
        while (y1mem.size() <= n) {
            double last = y1mem.get(y1mem.size() - 1);
            int s = y1mem.size();
            y1mem.add(last + 1.f / s);
        }
        return y1mem.get(n - 1);
    }

    public static double y2(int n) {
        if (n >= 500)
            return 1.f / n;
        while (y2mem.size() <= n) {
            double last = y2mem.get(y2mem.size() - 1);
            int s = y2mem.size();
            double v = 1. / s;
            double c = v * v;
            y2mem.add(last - c);
        }
        return y2mem.get(n - 1);
    }

    public static double y3(int n) {
        if (n >= 200) {
            double v = 1. / n;
            return v * v;
        }
        while (y3mem.size() <= n) {
            double last = y3mem.get(y3mem.size() - 1);
            int s = y3mem.size();
            double v = 1. / s;
            double c = v * v * v;
            y3mem.add(last + 2 * c);
        }
        return y3mem.get(n - 1);
    }

    public int a, b;

    public double mean;

    public double meanLogOdds;
    public double sdLogOdds;

    public double grad;

    public BetaDistribution(int a, int b) {
        this.a = a;
        this.b = b;
        updateStats();
    }

    public BetaDistribution() {
        this(1, 1);
    }

    void updateStats() {
        this.mean = (double) this.a / (this.a + this.b);
        this.meanLogOdds = y1(this.a) - y1(this.b + this.a);
        this.sdLogOdds = y2(this.a) - y2(this.b + this.a);

        double sum=this.a+this.b;
        double a=this.a;
        double b=this.b;

        this.grad = b/(a*sum*sum);
    }

    public void add(int a, int b) {
        this.a += a;
        this.b += b;
        updateStats();
    }

}

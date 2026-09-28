package edu.upc.lattice;

import com.koloboke.collect.map.IntIntCursor;
import com.koloboke.collect.map.hash.HashIntIntMap;
import com.koloboke.collect.map.hash.HashIntIntMaps;

public class PredSet {
    public static int MOD = 333_333_313;
    private HashIntIntMap preds;
    int hash;
    int[] predProd;

    public PredSet(int[] predProd) {
        this.preds = HashIntIntMaps.newMutableMap();
        this.predProd = predProd;
        this.hash = 0;
    }

    public PredSet(PredSet source) {
        this.preds = HashIntIntMaps.newMutableMap(source.preds);
        this.predProd = source.predProd;
        this.hash = source.hash;
    }

    int predScore(int cp, int p) {
        return (p+1) * this.predProd[cp];
    }

    public void add(int cp, int p) {
        assert !this.preds.containsKey(cp);
        this.preds.put(cp, p);
        this.hash = (this.hash + this.predScore(cp, p)) % PredSet.MOD;

    }

    public void remove(int cp, int p) {
        assert this.preds.containsKey(cp);
        int pp = this.preds.remove(cp);
        assert pp == p;
        this.hash = (this.hash - this.predScore(cp, p) + PredSet.MOD) % PredSet.MOD;

    }

    public boolean contains(int cp) {
        return this.preds.containsKey(cp);
    }

    public IntIntCursor cursor() {
        return this.preds.cursor();
    }

    public int size() {
        return this.preds.size();
    }

    @Override
    public int hashCode() {
        return this.hash;
    }

    @Override
    public boolean equals(Object obj) {
        return ((PredSet) obj).preds.equals(this.preds);
    }

    @Override
    public String toString() {
        return this.preds.toString();
    }
}

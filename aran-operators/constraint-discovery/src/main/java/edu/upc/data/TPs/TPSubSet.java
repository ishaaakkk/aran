package edu.upc.data.TPs;

import java.util.Arrays;

public class TPSubSet {
    public TPSet source;
    public int[] TPs;
    public int length;
    public TPSubSet(TPSet source) {
        this.source=source;
        this.length=source.length;
        this.TPs=new int[source.length];
        for(int i=0;i<length;i++)this.TPs[i]=i;
    }
    public TPSubSet(TPSubSet source) {
        this.source=source.source;
        this.length=0;
        this.TPs=new int[source.length];
    }

    public void resize(int length){
        this.length=length;
        this.TPs=Arrays.copyOf(TPs, length);
    }
    
}

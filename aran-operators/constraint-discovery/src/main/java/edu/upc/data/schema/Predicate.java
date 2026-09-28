package edu.upc.data.schema;

public class Predicate {
    
    public enum Operator {
        EQ,NE,GT,LE,LT,GE
    }
    public Operator op;
    public ColumnPair cols;
    public int ID;
    public int cID,pID;

    public Predicate(ColumnPair cols,Operator op,int ID ,int cID,int pID) {
        this.cols=cols;
        this.op=op;
        this.ID=ID;
        this.cID=cID;
        this.pID=pID;
    }
    
    @Override
    public String toString() {
        return this.cols.x.name+" "+this.op.toString()+" "+this.cols.y.name;
    }
}

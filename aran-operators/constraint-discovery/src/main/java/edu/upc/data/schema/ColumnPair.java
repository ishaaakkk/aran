package edu.upc.data.schema;

public class ColumnPair {
    public Column x,y;
    public Predicate[] preds;
    public Column.Type type;
    public int cID;

    public ColumnPair(Column x, Column y,int cID) {
        this.x=x;
        this.y=y;
        this.type=this.x.type;
        this.cID=cID;
    }
    

    @Override
    public String toString() {
        return this.x.name+" "+this.y.name;
    }
    
}

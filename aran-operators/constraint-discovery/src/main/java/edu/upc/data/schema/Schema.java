package edu.upc.data.schema;

import java.util.ArrayList;
import java.util.EnumMap;

import edu.upc.lattice.SchemaLattice;

public class Schema {

    public String[] colnames;

    public Column[] columns;
    public EnumMap<Column.Type,Column[]> typeColumns;
    
    public ColumnPair[] columnPairs;
    public EnumMap<Column.Type,ColumnPair[]> typeColumnPairs;

    public Predicate[] preds;

    public SchemaLattice lattice;
    


    public Schema(String[] colNames) {
        this(colNames, null);
    }

    public Schema(String[] colNames, Column.Type[] colTypes) {
        this.colnames=colNames;
        this.buildColumns(colNames, colTypes);
        this.buildColumnPairs();
        this.buildPredicates();
        this.buildLattice();
    }

    public void buildColumns(String[] colNames, Column.Type[] colTypes) {

        this.columns=new Column[colNames.length];
        for(int i=0;i<this.columns.length;i++) {
            if (colTypes != null && i < colTypes.length) {
                this.columns[i]=new Column(colNames[i], colTypes[i]);
            } else {
                this.columns[i]=new Column(colNames[i]);
            }
        }


        EnumMap<Column.Type,ArrayList<Column>> typeColumns=new EnumMap<>(Column.Type.class);
        for(Column.Type t:Column.Type.values()) typeColumns.put(t,new ArrayList<>());

        for(int i=0;i<this.columns.length;i++) {
            Column col=this.columns[i];
            ArrayList<Column> typeCols=typeColumns.get(col.type);
            col.ID=i;
            col.typeID=typeCols.size();
            typeCols.add(col);      
        }

        this.typeColumns=new EnumMap<>(Column.Type.class);
        for(Column.Type t:Column.Type.values()) {
            ArrayList<Column> typeCols=typeColumns.get(t);
            this.typeColumns.put(t, typeCols.toArray(new Column[typeCols.size()]));
        }
        
    }
    

    public void buildColumnPairs() {
        ArrayList<ColumnPair> columnPairs=new ArrayList<>();
        EnumMap<Column.Type,ArrayList<ColumnPair>> typeColumnPairs=new EnumMap<>(Column.Type.class);
        for(Column.Type t:Column.Type.values()) typeColumnPairs.put(t,new ArrayList<>());
        for(Column col:this.columns) {
            int cID=columnPairs.size();
            ColumnPair colPair=new ColumnPair(col, col,cID);
            columnPairs.add(colPair);
            typeColumnPairs.get(colPair.type).add(colPair);          
        }

        this.columnPairs=columnPairs.toArray(new ColumnPair[columnPairs.size()]);
        this.typeColumnPairs=new EnumMap<>(Column.Type.class);
        for(Column.Type t:Column.Type.values()) {
            ArrayList<ColumnPair> typeColPairs=typeColumnPairs.get(t);
            this.typeColumnPairs.put(t, typeColPairs.toArray(new ColumnPair[typeColPairs.size()]));
        }
    }


    public void buildPredicates() {

        ArrayList<Predicate> predicates=new ArrayList<>();

        Predicate.Operator[] ops=Predicate.Operator.values();
        int numPreds=0;
        for(ColumnPair colPair:this.columnPairs) {
            colPair.preds=new Predicate[colPair.type==Column.Type.STRING?2:6];
            for(int i=0;i<colPair.preds.length;i++) {
                colPair.preds[i]=new Predicate(colPair, ops[i],numPreds++,colPair.cID,i);
                predicates.add(colPair.preds[i]);
            }
        }
        this.preds=predicates.toArray(new Predicate[0]);
    }


    public void buildLattice() {
        int[] preds=new int[this.columnPairs.length];
        for(int cp=0;cp<this.columnPairs.length;cp++) preds[cp]=this.columnPairs[cp].preds.length;
        this.lattice=new SchemaLattice(preds);
    }
    /**
     * Compares the column sequence by value.
     *
     * <p>The same two defects {@link Column#equals} was corrected for were left here: the cast
     * had no {@code instanceof} guard, so a foreign type threw ClassCastException and
     * {@code null} threw NullPointerException, where {@code equals} is required to return false.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Schema other)) return false;

        if(this.columns.length!=other.columns.length) return false;

        for(int i=0;i<this.columns.length;i++)
        {
            if(!this.columns[i].equals(other.columns[i])) return false;
        }
        return true;
    }

    /**
     * Required alongside {@link #equals}: without it, hash-based collections lose entries.
     *
     * <p>Schema is a map key in the lattice search, so the missing override was not a style
     * nit — two equal schemas hashed to different buckets and the lookup silently missed.
     * Must agree with {@code equals}, which compares the column sequence, so it hashes the
     * same array in the same order.
     */
    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(this.columns);
    }
}

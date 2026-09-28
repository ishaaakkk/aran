package edu.upc.data.schema;

public class Column {
    public enum Type {
        STRING, REAL, INTEGER
    }
    public String name;
    public Type type;
    public int ID,typeID;

    public Column(String name, Type type) 
    {
        this.name=name;
        this.type=type;
    }

    public Column(String name) 
    {
        this.name=name;
           
        if (this.name.contains("Double")) {
            this.type=Type.REAL;
        }
        else if (this.name.contains("Integer")) {
            this.type=Type.INTEGER;
        }
        else {
            this.type=Type.STRING;
        }
    }


    /**
     * Compares name and type by value.
     *
     * <p>This previously compared names with {@code ==}, i.e. by reference. That happens to
     * work for interned literals and silently fails for names built at runtime — such as
     * headers parsed out of a CSV, which is how columns actually reach this class — so two
     * columns with identical names could compare unequal.
     *
     * <p>It also cast without an {@code instanceof} check, throwing ClassCastException for a
     * foreign type and NullPointerException for {@code null}, both of which {@code equals} is
     * required to handle by returning false.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Column other)) return false;
        return this.type == other.type && java.util.Objects.equals(this.name, other.name);
    }

    /** Required alongside {@link #equals}: without it, hash-based collections lose entries. */
    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, type);
    }
}

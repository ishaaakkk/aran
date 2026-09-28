package edu.upc.data.dataset;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.SplittableRandom;

import com.csvreader.CsvReader;
import com.koloboke.collect.map.hash.HashObjIntMap;
import com.koloboke.collect.map.hash.HashObjIntMaps;

import edu.upc.data.TPs.TPSet;
import edu.upc.data.TPs.TPSubSet;
import edu.upc.data.schema.Column;
import edu.upc.data.schema.Predicate;
import edu.upc.data.schema.Schema;

public class CSVDataset extends RelationalDataset{

    
    
    BufferedReader reader;
    CsvReader csvReader;

    SplittableRandom random;


    public CSVDataset(String path,int size,long seed) throws IOException {
        this.reader= new BufferedReader(new FileReader(path));
        char delimiter = detectDelimiter(path);
        this.csvReader=new CsvReader(this.reader, delimiter);
        this.csvReader.readHeaders();
        
        String[] headers = csvReader.getHeaders();
        Column.Type[] inferredTypes = detectColumnTypes(path, headers, delimiter);
        this.schema=new Schema(headers, inferredTypes);
        
        this.size=size;
        this.buildColumns();
        this.random=new SplittableRandom(seed);
    }

    /**
     * Samples the first few records to dynamically infer the correct column types.
     */
    private static Column.Type[] detectColumnTypes(String path, String[] headers, char delimiter) throws IOException {
        int numCols = headers.length;
        Column.Type[] types = new Column.Type[numCols];
        
        boolean[] allInt = new boolean[numCols];
        boolean[] allReal = new boolean[numCols];
        boolean[] hasValue = new boolean[numCols];
        for (int i = 0; i < numCols; i++) {
            allInt[i] = true;
            allReal[i] = true;
            hasValue[i] = false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            CsvReader csvReader = new CsvReader(reader, delimiter);
            csvReader.readHeaders();

            int sampleCount = 0;
            while (sampleCount < 100 && csvReader.readRecord()) {
                String[] values = csvReader.getValues();
                if (values == null || values.length == 0) continue;
                
                for (int i = 0; i < numCols; i++) {
                    if (i >= values.length) continue;
                    String val = values[i].trim();
                    if (val.isEmpty()) continue;
                    
                    hasValue[i] = true;
                    
                    if (allInt[i]) {
                        try {
                            Integer.parseInt(val);
                        } catch (NumberFormatException e) {
                            allInt[i] = false;
                        }
                    }
                    if (allReal[i]) {
                        try {
                            Float.parseFloat(val);
                        } catch (NumberFormatException e) {
                            allReal[i] = false;
                        }
                    }
                }
                sampleCount++;
            }
        } catch (Exception e) {
            // No sampled value for a column past this point: fall through to the
            // header-name heuristic below rather than failing the whole inference.
        }

        for (int i = 0; i < numCols; i++) {
            if (!hasValue[i]) {
                String name = headers[i];
                if (name.contains("Double")) {
                    types[i] = Column.Type.REAL;
                } else if (name.contains("Integer")) {
                    types[i] = Column.Type.INTEGER;
                } else {
                    types[i] = Column.Type.STRING;
                }
            } else if (allInt[i]) {
                types[i] = Column.Type.INTEGER;
            } else if (allReal[i]) {
                types[i] = Column.Type.REAL;
            } else {
                types[i] = Column.Type.STRING;
            }
        }
        return types;
    }

    /**
     * Peeks at the first line of the file to determine the most likely delimiter.
     * Supports comma, semicolon, tab, and pipe.
     */
    private static char detectDelimiter(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String firstLine = br.readLine();
            if (firstLine == null) return ',';
            char[] candidates = {',', ';', '\t', '|'};
            int maxCount = -1;
            char best = ',';
            for (char c : candidates) {
                int count = 0;
                for (int i = 0; i < firstLine.length(); i++) {
                    if (firstLine.charAt(i) == c) count++;
                }
                if (count > maxCount) {
                    maxCount = count;
                    best = c;
                }
            }
            return best;
        }
    }

    public int[][] intData;
    public float[][] realData;
    public int[][] stringData;
    
    HashObjIntMap<String> stringMap=HashObjIntMaps.newUpdatableMap();
    ArrayList<String> invStringMap=new ArrayList<>();

        
    void buildColumns()  throws IOException{

        int n=this.size;

        int defVal=this.stringMap.defaultValue();

        this.constColumn=new boolean[this.schema.columns.length];
        for(int i=0;i<constColumn.length;i++)constColumn[i]=true;

        Column[] intCols=schema.typeColumns.get(Column.Type.INTEGER);
        int numIntCols=intCols.length;
        this.intData=new int[numIntCols][n];
        int[] intColID=new int[numIntCols];
        for(int i=0;i<numIntCols;i++) {
            intColID[i]=intCols[i].ID;
        }
        
        Column[] realCols=schema.typeColumns.get(Column.Type.REAL);
        int numRealCols=realCols.length;
        this.realData=new float[numRealCols][n];
        int[] realColID=new int[numRealCols];
        for(int i=0;i<numRealCols;i++) {
            realColID[i]=realCols[i].ID;
        }
        Column[] strCols=schema.typeColumns.get(Column.Type.STRING);
        int numStrCols=strCols.length;
        this.stringData=new int[numStrCols][n];
        int[] strColID=new int[numStrCols];
        for(int i=0;i<numStrCols;i++) {
            strColID[i]=strCols[i].ID;
        }

        int actualRows = 0;
        for(int row=0;row<n;row++){
            if (!csvReader.readRecord()) break;   // EOF reached before n rows
            String[] fields=this.csvReader.getValues();
            if (fields == null || fields.length == 0) break;  // malformed/empty line

            for(int i=0;i<numIntCols;i++){
                int colID=intColID[i];
                if (colID >= fields.length) { intData[i][row]=0; continue; }
                try { intData[i][row]=Integer.parseInt(fields[colID].trim()); }
                catch(NumberFormatException e){ intData[i][row]=0; }
                if(constColumn[colID]) {
                    if(intData[i][row]!=intData[i][0])constColumn[colID]=false;
                }
            }
            for(int i=0;i<numRealCols;i++){
                int colID=realColID[i];
                if (colID >= fields.length) { realData[i][row]=Float.NaN; continue; }
                String field=fields[colID].trim();
                try { realData[i][row]= field.length()>0?Float.parseFloat(field):Float.NaN; }
                catch(NumberFormatException e){ realData[i][row]=Float.NaN; }
                if(constColumn[colID]) {
                    if(realData[i][row]!=realData[i][0])constColumn[colID]=false;
                }
            }
            for(int i=0;i<numStrCols;i++){
                int colID=strColID[i];
                String k = colID < fields.length ? fields[colID] : "";
                int v=this.stringMap.size()+1;
                int prev=this.stringMap.putIfAbsent(k, v);
                if(prev!=defVal){
                    v=prev;
                } 
                stringData[i][row]=v;
                if(constColumn[colID]) {
                    if(stringData[i][row]!=stringData[i][0])constColumn[colID]=false;
                }
            }
            actualRows++;
        }
        // If we hit EOF before n rows, shrink size to what we actually read
        if (actualRows < n) this.size = actualRows;
    }

    @Override
    public TPSubSet filter(TPSubSet TPs, Predicate pred) {
        TPSubSet newTPSubset=new TPSubSet(TPs.source);
        int[] tpx=TPs.source.x,tpy=TPs.source.y;
        int[] oldTPs=TPs.TPs;
        int[] newTPs=newTPSubset.TPs;
        int newLength=0;
        switch (pred.cols.type) {
            case STRING:
                int[] sdx=this.stringData[pred.cols.x.typeID];
                int[] sdy=this.stringData[pred.cols.y.typeID];
                switch (pred.op) {
                    case EQ:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(sdx[tx]==sdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case NE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(sdx[tx]!=sdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    default:
                        assert false;                
                }                
                break;



            case INTEGER:
                int[] idx=this.intData[pred.cols.x.typeID];
                int[] idy=this.intData[pred.cols.y.typeID];
                switch (pred.op) {
                    case EQ:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]==idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case NE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]!=idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case GT:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]>idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case LE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]<=idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case LT:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]<idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case GE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(idx[tx]>=idy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    default:
                        assert false;                
                }                
                break;



            case REAL:
                float[] rdx=this.realData[pred.cols.x.typeID];
                float[] rdy=this.realData[pred.cols.y.typeID];
                switch (pred.op) {
                    case EQ:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]==rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case NE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]!=rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case GT:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]>rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case LE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]<=rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case LT:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]<rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    case GE:                        
                        for(int tp:oldTPs) {
                            int tx=tpx[tp],ty=tpy[tp];
                            if(rdx[tx]>=rdy[ty]) newTPs[newLength++]=tp;
                        }                        
                        break;
                    default:
                        assert false;                
                }                
                break;        
            default:
                assert false;
        }

        newTPSubset.resize(newLength);
        return newTPSubset;
    }

    @Override
    public TPSubSet sample(int n) {

        TPSet TPSet=new TPSet(n);
        for(int i=0;i<n;i++) {
            int x =random.nextInt(this.size);
            int y=random.nextInt(this.size-1);
            if(y>=x)y++;
            TPSet.x[i]=x;
            TPSet.y[i]=y;
        }
        TPSubSet TPs=new TPSubSet(TPSet);
        return TPs;
    }

    
    
}

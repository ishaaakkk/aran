package edu.upc.lattice;


import com.koloboke.collect.map.hash.HashIntObjMap;
import com.koloboke.collect.map.hash.HashIntObjMaps;
import com.koloboke.collect.map.hash.HashObjObjMap;
import com.koloboke.collect.map.hash.HashObjObjMaps;


public class Lattice<N,E> {



    public class Node {


        public N n;
        public HashIntObjMap<Edge[]> to;
        public HashIntObjMap<Edge> from;
        public PredSet preds;

        public Lattice<?,?>.Node base;


        public Node(PredSet preds) {
            this.preds=preds;
            this.from=HashIntObjMaps.newUpdatableMap();
            this.to=HashIntObjMaps.newUpdatableMap();
        }

        @Override
        public String toString() {
            return this.preds.toString();
        }


        public Edge getTo(int cp, int p){return Lattice.this.getTo(this, cp, p);}
        public Edge fetchTo(int cp, int p){return Lattice.this.fetchTo(this, cp, p);}
        public Edge fetchFrom(int cp, int p){return Lattice.this.fetchFrom(this, cp, p);}

    }


    public class Edge {

        public E e;

        public Lattice<?,?>.Edge base;

        public Node from,to;
        public int cp,p;
        public Edge(Node from, int cp, int p, Node to) {
            this.cp=cp;
            this.from=from;
            this.p=p;
            this.to=to;
        }
        @Override
        public String toString() {
            return this.from.preds.toString()+" + ("+Integer.toString(this.cp)+"="+Integer.toString(this.p)+")";
        }
    }





    public int[] preds,predProd;

    public HashObjObjMap<PredSet,Node> nodes;

    public Node root;

    public Lattice<?,?> base;
    
    public Lattice(int[] preds) {
        this.base=this;
        this.preds=preds;    

        this.predProd=new int[preds.length];
        this.predProd[0]=1;
        for(int p=1;p<preds.length;p++)this.predProd[p]=(this.predProd[p-1]*(this.preds[p-1]+1)) % PredSet.MOD;

        this.nodes=HashObjObjMaps.newUpdatableMap();
        this.root=new Node(new PredSet(this.predProd));
    }

    public Lattice(Lattice<?,?> base) {
        this.base=base;
        this.preds=base.preds;

        this.predProd=base.predProd;

        this.nodes=HashObjObjMaps.newUpdatableMap();
        this.root=new Node(new PredSet(this.predProd));

    }


    public Node fetchRoot() {
        return this.fetchNode(new PredSet(this.predProd));
    }

    public Node fetchNode(PredSet preds) {
        Node n=this.nodes.get(preds);
        if(n==null) {
            if(preds.size()==0)n=this.root;
            else {
                n=new Node(preds);                
            }
            this.nodes.put(n.preds, n);
            n.base=this.base==this?n:this.base.fetchNode(preds);
        }
        return n;
    }


    public Edge getTo(Node n, int cp, int p) {
        Edge[] ecps=n.to.get(cp);
        if(ecps!=null) return ecps[p];
        return null;
    }
    Edge[] fetchToEdges(Node n, int cp) {
        Edge[] ecps=n.to.get(cp);
        assert !n.preds.contains(cp);
        if(ecps==null)
        {
            ecps=(Edge[]) new Lattice<?,?>.Edge[Lattice.this.preds[cp]];
            n.to.put(cp, ecps);
        }
        return ecps;
    }
    public Edge fetchTo(Node n, int cp, int p) {
        
        Edge[] ecps=this.fetchToEdges(n, cp);
        Edge e=ecps[p];
        
        if(e==null) {
            
            PredSet newPreds=new PredSet(n.preds);
            assert !newPreds.contains(cp);
            newPreds.add(cp, p);
            Node toNode=this.fetchNode(newPreds);
            e=new Edge(n, cp, p, toNode);
            linkEdge(e);
            e.base=this.base==this?e:n.base.fetchTo(e.cp,e.p);            
            
        }
        
        return e;
    }
    public Edge fetchFrom(Node n, int cp, int p) {
        Edge e=n.from.get(cp);        
        if(e==null) {
            PredSet newPreds=new PredSet(n.preds);
            assert newPreds.contains(cp);
            newPreds.remove(cp, p);
            Node fromNode=this.fetchNode(newPreds);
            e=new Edge(fromNode, cp, p, n);
            linkEdge(e);
            e.base=this.base==this?e:this.base.fetchNode(e.from.preds).fetchTo(e.cp,e.p);
        }
        assert e.p==p;
        return e;
    }

    void linkEdge(Edge e) {
        
        assert !e.to.from.containsKey(e.cp);
        e.to.from.put(e.cp, e);
        
        Edge[] toEdges=this.fetchToEdges(e.from, e.cp);

        assert toEdges[e.p]==null;
        toEdges[e.p]=e;
    }
}

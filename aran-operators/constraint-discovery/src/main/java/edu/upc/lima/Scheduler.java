package edu.upc.lima;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import com.koloboke.collect.map.IntIntCursor;
import com.koloboke.collect.map.IntObjCursor;

import edu.upc.data.TPs.TPSubSet;
import edu.upc.data.dataset.RelationalDataset;
import edu.upc.data.schema.Predicate;
import edu.upc.data.schema.Predicate.Operator;
import edu.upc.lattice.Lattice;
import edu.upc.lattice.PredSet;
import edu.upc.lattice.SchemaLattice;
import edu.upc.lattice.Lattice.Edge;
import edu.upc.utils.BetaDistribution;

public class Scheduler {

    public static class SchedulerNode {

        BetaDistribution dist=new BetaDistribution();
        boolean satisfied=false;
        ArrayList<TPSubSet> TPs;

    }

    public static class SchedulerEdge {
        BetaDistribution dist=new BetaDistribution();
        boolean sound=false;
    }
    public class SchedulerLattice extends Lattice<SchedulerNode,SchedulerEdge> {

        public SchedulerLattice(Lattice<?, ?> base) {
            super(base);
        }

    }

    public static double minGrad=1e-6;
    public RelationalDataset dataset;
    public SchedulerLattice schedulerLattice;
    public Scheduler(RelationalDataset dataset) {
        this.dataset=dataset;
        this.schedulerLattice=new SchedulerLattice(dataset.schema.lattice);
    }


    SchedulerNode getNode(SchedulerLattice.Node node) {
        if(node.n==null)
        {
            node.n=new SchedulerNode();
        }
        return node.n;
    }

    SchedulerEdge getEdge(SchedulerLattice.Edge edge) {
        if(edge.e==null)
        {
            edge.e=new SchedulerEdge();
        }
        return edge.e;
    }

    int getBatchSize(int batch) {
        int maxBatch=20;
        if(batch>maxBatch)batch=maxBatch;
        int batchSize= 100*(1<<batch);
        return batchSize;
    }



    public java.util.List<String> populatePredicates() {

        Predicate[] preds=this.dataset.schema.preds;
        SchedulerNode[] predNodes=new SchedulerNode[preds.length];
        ArrayList<Integer> predIDs=new ArrayList<>(preds.length);
        for(int i=0;i<preds.length;i++){
            Predicate pred=preds[i];
            SchedulerLattice.Edge e=this.schedulerLattice.fetchRoot().fetchTo(preds[i].cID,preds[i].pID);
            SchedulerLattice.Node n=e.to;
            SchedulerNode sn=this.getNode(n);
            SchedulerEdge se=this.getEdge(e);

            predNodes[i]=sn;
            sn.TPs=new ArrayList<>();

            se.sound=true;           

            

            if(this.dataset.constColumn[pred.cols.x.ID] && (pred.op==Operator.NE || pred.op==Operator.GT || pred.op==Operator.LT))
            {
                sn.dist.add(0, 1000000000);

            }
            else {
                predIDs.add(i);
            }

            
        }

        double nn=this.dataset.size;
        nn=nn*nn*0.0325;
        long totalN=0;
        int batches= 0;
        while (totalN<nn) {
            totalN+=getBatchSize(batches);
            batches+=1;
        }
        for(int batch=0;batch<batches;batch++) {
            ArrayList<Integer> nextPredIDs=new ArrayList<>(predIDs.size());

            int batchSize=this.getBatchSize(batch);
            TPSubSet TPs=dataset.sample(batchSize);
            for(int predID:predIDs) {
                Predicate pred=preds[predID];
                SchedulerNode predNode=predNodes[predID];
                SchedulerEdge predEdge=this.getEdge(this.schedulerLattice.fetchRoot().fetchTo(pred.cID, pred.pID));

                TPSubSet predTPs=this.dataset.filter(TPs, pred);
                predNode.TPs.add(predTPs);

                int a=predTPs.length;
                int b=batchSize-a;
                predNode.dist.add(a, b);
                predEdge.dist.add(a, b);

                if(predNode.dist.grad>minGrad) {
                    nextPredIDs.add(predID);
                }
            }

            predIDs=nextPredIDs;
            if(predIDs.size()==0)break;
        }


        Integer[] sortedPreds=new Integer[preds.length];
        for(int i=0;i<preds.length;i++)sortedPreds[i]=i;
        Arrays.sort(sortedPreds,new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return -Double.compare(predNodes[a].dist.meanLogOdds,predNodes[b].dist.meanLogOdds);
            }
            
        });


        ArrayList<ResultEntry> res=new ArrayList<>();

        for(int newPredSortedID=0;newPredSortedID<preds.length;newPredSortedID++) {
            int newPredIDx=sortedPreds[newPredSortedID];
            Predicate newPred=preds[newPredIDx];
            SchedulerLattice.Node node=this.schedulerLattice.fetchRoot().getTo(newPred.cID, newPred.pID).to;

            this.search(node, newPredSortedID, sortedPreds, preds,res);
        }


        java.util.List<String> output = new java.util.ArrayList<>();
        for(ResultEntry ps:res) {

            StringBuilder s = new StringBuilder("!(");
            boolean first = true;
            for(IntIntCursor cur=ps.e.to.preds.cursor();cur.moveNext();) {
                int cp=cur.key();
                int p=cur.value();
                if (!first) {
                    s.append(" & ");
                }
                s.append(this.dataset.schema.columnPairs[cp].preds[p].toString());
                first = false;
            }
            s.append(")");
            output.add(s.toString());

        }

        return output;

    }


    public static class ResultEntry {
        SchedulerLattice.Edge e;
        double s;

        public ResultEntry(SchedulerLattice.Edge e, double s) {
            this.e=e;
            this.s=s;
        }

    }

    void search(SchedulerLattice.Node node,int lastPredIdx, Integer[] predIDXs,Predicate[] preds,ArrayList<ResultEntry> res) {

        for(int newPredSortedID=0;newPredSortedID<lastPredIdx;newPredSortedID++) {
            int newPredIDx=predIDXs[newPredSortedID];
            Predicate newPred=preds[newPredIDx];
            if(node.preds.contains(newPred.cID))continue;



            if(exploreNode(node,newPred.cID,newPred.pID))
            {
                SchedulerLattice.Edge toE=node.fetchTo(newPred.cID, newPred.pID);
                SchedulerLattice.Node toN=toE.to;
                this.propagateAcross(toE,res);
                search(toN, newPredSortedID, predIDXs, preds,res);
                this.getNode(toN).TPs=null;
                
            }


            

        }
    }


    void propagateAcross(SchedulerLattice.Edge e,ArrayList<ResultEntry> res) {

        SchedulerNode fn=this.getNode(e.from);
        SchedulerNode tn=this.getNode(e.to);

        SchedulerEdge se=this.getEdge(e);
        

        tn.TPs=new ArrayList<>();
        int a=0,b=0;
        for(TPSubSet TPs:fn.TPs) {
            TPSubSet filteredTPs=this.dataset.filter(TPs, this.dataset.schema.columnPairs[e.cp].preds[e.p]);
            tn.TPs.add(filteredTPs);

            a+=filteredTPs.length;
            b+=TPs.source.length-filteredTPs.length;          
            
        }
        tn.dist.add(a, b);
        se.dist.add(tn.dist.a-1, fn.dist.a-tn.dist.a);
        

        se.sound=true;

        double minlogProbDist=1e10;

        for(IntIntCursor cur=e.from.preds.cursor();cur.moveNext();) {
            int curCP=cur.key();
            int curP=cur.value();
            SchedulerLattice.Edge fEdge= e.from.fetchFrom(curCP, curP);
            SchedulerLattice.Node fNode=fEdge.from;
            SchedulerLattice.Edge tEdge=fNode.fetchTo(e.cp, e.p);

            SchedulerEdge tSEdge=this.getEdge(tEdge);

            BetaDistribution lower=tSEdge.dist;
            BetaDistribution upper=se.dist;

            double combinedSdLogOdds=Math.sqrt(lower.sdLogOdds*lower.sdLogOdds+upper.sdLogOdds*upper.sdLogOdds);
            double probDist=lower.meanLogOdds-upper.meanLogOdds;
            double normDist= probDist/combinedSdLogOdds;

            double biasCorrection=0.8;
            if(upper.mean>biasCorrection) normDist=normDist*Math.exp(-(1-biasCorrection)/(1-upper.mean));

            if(normDist<minlogProbDist) minlogProbDist=normDist;

            if(normDist<2)
            {
                se.sound=false;
                break;
            }

        }

        if(se.sound && se.dist.a==1) {
            ResultEntry resultEntry=new ResultEntry(e,minlogProbDist);
            res.add(resultEntry);
        }




    }
    boolean exploreNode(SchedulerLattice.Node n, int cp, int p) {
        if(this.getNode(n).dist.a==1) return false;


        for(IntIntCursor cur=n.preds.cursor();cur.moveNext();) {
            int curCP=cur.key();
            int curP=cur.value();
            SchedulerLattice.Edge fEdge= n.fetchFrom(curCP, curP);
            SchedulerLattice.Node fNode=fEdge.from;
            SchedulerLattice.Edge tEdge=fNode.fetchTo(cp, p);
            SchedulerLattice.Node tNode=tEdge.to;

            SchedulerEdge tSEdge=this.getEdge(tEdge);

            if(!tSEdge.sound) return false;
            

            SchedulerNode tSNode=this.getNode(tNode);
            if(tSNode.dist.a==1) return false;

        }


        return true;
    }





}

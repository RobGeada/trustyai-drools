package drools_integrators;

import org.apache.commons.math3.util.Pair;
import org.drools.core.reteoo.AbstractTerminalNode;
import org.drools.core.reteoo.AccumulateNode;
import org.drools.core.reteoo.AlphaNode;
import org.drools.core.reteoo.BetaNode;
import org.drools.core.reteoo.EntryPointNode;
import org.drools.core.reteoo.JoinNode;
import org.drools.core.reteoo.LeftInputAdapterNode;
import org.drools.core.reteoo.LeftTupleSource;
import org.drools.core.reteoo.NotNode;
import org.drools.core.reteoo.ObjectSource;
import org.drools.core.reteoo.ObjectTypeNode;
import org.drools.core.reteoo.RightInputAdapterNode;
import org.drools.core.reteoo.RuleTerminalNode;
import org.drools.core.spi.BetaNodeFieldConstraint;
import org.drools.mvel.MVELConstraint;

import java.util.ArrayList;
import java.util.Map;

public class ReteTraverser {
    // entry point to graph parser. From a terminal node, walk upwards through parent recursively
    public static void parseTerminalNode(AbstractTerminalNode terminalNode, RuleFireListener ruleTracker, RuleContext ruleContext, ParserContext dpc){
        RuleTerminalNode node = (RuleTerminalNode) terminalNode;
        dpc.currentTerminals = new ArrayList<>();
        if (ruleTracker.getDifferences().containsKey(node.getRule())) {
            for (Map.Entry<String, Pair<Object, Object>> entry : ruleTracker.getDifferences().get(node.getRule()).entrySet()) {
                GraphNode subGraphNode = new GraphNode(
                        "Terminal: " + node.getRule().getName(),
                        ruleContext,
                        node.getId(),
                        entry.getKey(),
                        entry.getValue().getSecond());
                GraphNode addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
                parseLeftTupleSource(node.getLeftTupleSource(), subGraphNode, ruleContext, dpc);
                dpc.currentTerminals.add(addedNode);
            }
        } else {
            GraphNode subGraphNode = new GraphNode(
                    "Terminal: " + node.getRule().getName(),
                    ruleContext,
                    node.getId());
            GraphNode addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseLeftTupleSource(node.getLeftTupleSource(), subGraphNode, ruleContext, dpc);
            dpc.currentTerminals.add(addedNode);
        }
    }
    // from an object source, walk upwards through parent recursively
    public static void parseObjectSource(ObjectSource objectSource, GraphNode child, RuleContext ruleContext, ParserContext dpc){
        GraphNode subGraphNode = null;
        GraphNode addedNode = null;
        if (objectSource instanceof AlphaNode) {
            AlphaNode node = (AlphaNode) objectSource;
            MVELConstraint mvelConstraint = (MVELConstraint) node.getConstraint();
            subGraphNode = new GraphNode("Alpha: "+mvelConstraint.getExpression(), ruleContext, node.getId());
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(node.getParentObjectSource(), addedNode, ruleContext, dpc);
        } else if (objectSource instanceof RightInputAdapterNode) {
            RightInputAdapterNode node = (RightInputAdapterNode) objectSource;
            subGraphNode = new GraphNode("RightInput: " + node.getLeftTupleSource().getObjectType().getClassName(), ruleContext, node.getId());
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseLeftTupleSource(node.getLeftTupleSource(), addedNode, ruleContext, dpc);
        } else if (objectSource instanceof EntryPointNode) {
            EntryPointNode node = (EntryPointNode) objectSource;
            subGraphNode = new GraphNode("EntryPoint", ruleContext, node.getId());
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            for (GraphNode previousTerminalNode : dpc.previousTerminals){
                dpc.graph.addEdge(previousTerminalNode, addedNode);
            }
            dpc.previousTerminals = dpc.currentTerminals;
        } else if (objectSource instanceof ObjectTypeNode){
            ObjectTypeNode objectTypeNode = (ObjectTypeNode) objectSource;
            subGraphNode = new GraphNode("ObjectTypeNode: " + objectTypeNode.getObjectType().getClassName(), ruleContext, objectTypeNode.getId());
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(objectTypeNode.getParentObjectSource(), addedNode, ruleContext, dpc);
        } else {
            System.out.println("ObjectSource other: "+objectSource);
        }
        dpc.graph.addEdge(addedNode, child);
    }

    // from an object source, walk upwards through parent(s) recursively
    public static void parseLeftTupleSource(LeftTupleSource leftTupleSinkNode, GraphNode child, RuleContext ruleContext, ParserContext dpc) {
        GraphNode subGraphNode = null;
        BetaNode betaNode = null;
        GraphNode addedNode = null;
        if (leftTupleSinkNode instanceof NotNode) {
            NotNode node = (NotNode) leftTupleSinkNode;
            StringBuilder constraintNames = new StringBuilder();
            for (BetaNodeFieldConstraint bnfc : node.getConstraints()) {
                MVELConstraint mvelConstraint = (MVELConstraint) bnfc;
                constraintNames.append(mvelConstraint.getExpression());
            }
            subGraphNode = new GraphNode("Not " + constraintNames, ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof JoinNode) {
            JoinNode node = (JoinNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("Join", ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof AccumulateNode) {
            AccumulateNode node = (AccumulateNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("Accumulate", ruleContext, node.getId());
            betaNode = (BetaNode) node;
        } else if (leftTupleSinkNode instanceof LeftInputAdapterNode) {
            LeftInputAdapterNode node = (LeftInputAdapterNode) leftTupleSinkNode;
            subGraphNode = new GraphNode("LeftInput: "+ node.getObjectType().getClassName(), ruleContext, node.getId());
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(node.getObjectSource(), addedNode, ruleContext, dpc);
        } else {
            System.out.println("Left Tuple Source Other:"+ leftTupleSinkNode);
        }

        if (betaNode != null && subGraphNode != null) {
            addedNode = Utils.nodeAdd(dpc.graph, dpc.graphNodeMap, subGraphNode);
            parseObjectSource(betaNode.getRightInput(), addedNode, ruleContext, dpc);
            parseLeftTupleSource(betaNode.getLeftTupleSource(), addedNode, ruleContext, dpc);
        }
        //System.out.println("contains new node "+subGraphNode + ": " + dpc.graph.containsVertex(subGraphNode));
        //System.out.println("contains child node "+subGraphNode + ": " + dpc.graph.containsVertex(child));
        dpc.graph.addEdge(addedNode, child);
    }
}

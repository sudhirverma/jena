/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.tdb2.solver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.atlas.lib.tuple.Tuple;
import org.apache.jena.atlas.lib.tuple.TupleFactory;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.tdb2.TDBException;
import org.apache.jena.tdb2.store.DatasetGraphTDB;
import org.apache.jena.tdb2.store.GraphTDB;
import org.apache.jena.tdb2.store.NodeId;
import org.apache.jena.tdb2.store.nodetable.NodeTable;
import org.apache.jena.tdb2.store.nodetupletable.NodeTupleTable;

/** Entry to the basic pattern solver for TDB2 */
public class Solver {

    /** Non-reordering execution of a basic graph pattern, given an iterator of bindings as input */
    public static QueryIterator execute(GraphTDB graph, BasicPattern pattern,
                                        QueryIterator input, Predicate<Tuple<NodeId>> filter,
                                        ExecutionContext execCxt)
    {
        // Maybe default graph or named graph.
        NodeTupleTable ntt = graph.getNodeTupleTable();
        return execute(ntt, graph.getGraphName(), pattern, input, filter, execCxt);
    }

    /** Non-reordering execution of a quad pattern, given an iterator of bindings as input.
     *  GraphNode is Node.ANY for execution over the union of named graphs.
     *  GraphNode is null for execution over the real default graph.
     */
    public static QueryIterator execute(DatasetGraphTDB ds, Node graphNode, BasicPattern pattern,
                                        QueryIterator input, Predicate<Tuple<NodeId>> filter,
                                        ExecutionContext execCxt)
    {
        NodeTupleTable ntt = ds.chooseNodeTupleTable(graphNode);
        return execute(ntt, graphNode, pattern, input, filter, execCxt);
    }

    // The worker.  Callers choose the NodeTupleTable.
    //     graphNode may be Node.ANY, meaning we should make triples unique.
    //     graphNode may be null, meaning default graph

    private static QueryIterator execute(NodeTupleTable nodeTupleTable, Node graphNode, BasicPattern pattern,
                                         QueryIterator input, Predicate<Tuple<NodeId>> filter,
                                         ExecutionContext execCxt)
    {
        if ( Quad.isUnionGraph(graphNode) )
            graphNode = Node.ANY;
        if ( Quad.isDefaultGraph(graphNode) )
            graphNode = null;

        List<Triple> triples = pattern.getList();
        boolean anyGraph = (graphNode == null ? false : (Node.ANY.equals(graphNode)));

        int tupleLen = nodeTupleTable.getTupleTable().getTupleLen();
        if ( graphNode == null ) {
            if ( 3 != tupleLen )
                throw new TDBException("SolverLib: Null graph node but tuples are of length " + tupleLen);
        } else {
            if ( 4 != tupleLen )
                throw new TDBException("SolverLib: Graph node specified but tuples are of length " + tupleLen);
        }

        // Convert from a QueryIterator (Bindings of Var/Node) to BindingNodeId
        NodeTable nodeTable = nodeTupleTable.getNodeTable();

        if ( false ) {
            List<Binding> x = Iter.toList(input);
            System.out.println(x);
            input = new QueryIterPlainWrapper(x.iterator());
        }

        Iterator<BindingNodeId> chain = Iter.map(input, SolverLib.convFromBinding(nodeTable));
        if ( false ) {
            List<BindingNodeId> x = Iter.toList(chain);
            System.out.println(x);
            chain = x.iterator();
        }

        List<Abortable> killList = new ArrayList<>();

        for ( Triple triple : triples ) {
            Tuple<Node> patternTuple = null;
            if ( graphNode == null )
                // 3-tuples
                patternTuple = TupleFactory.create3(triple.getSubject(), triple.getPredicate(), triple.getObject());
            else
                // 4-tuples.
                patternTuple = TupleFactory.create4(graphNode, triple.getSubject(), triple.getPredicate(), triple.getObject());
            // Plain RDF, no RDF-star
            // chain = solve(nodeTupleTable, tuple, anyGraph, chain, filter, execCxt)
            // ;
            // RDF-star SA
            chain = matchQuadPattern(chain, graphNode, triple, nodeTupleTable, patternTuple, anyGraph, filter, execCxt);

            chain = SolverLib.makeAbortable(chain, killList);
        }

        // DEBUG POINT
        if ( false ) {
            if ( chain.hasNext() )
                chain = Iter.debug(chain);
            else
                System.out.println("No results");
        }

        // Timeout wrapper ****
        // QueryIterTDB gets called async.
        // Iter.abortable?
        // Or each iterator has a place to test.
        // or pass in a thing to test?

        // Need to make sure the bindings here point to parent.
        Iterator<Binding> iterBinding = SolverLib.convertToNodes(chain, nodeTable);

        // "input" will be closed by QueryIterTDB but is otherwise unused.
        // "killList" will be aborted on timeout.
        return new QueryIterTDB(iterBinding, killList, input, execCxt);
    }

    /*development only*/
    public
    static Iterator<BindingNodeId> matchQuadPattern(Iterator<BindingNodeId> chain, Node graphNode, Triple tPattern,
                                                            NodeTupleTable nodeTupleTable, Tuple<Node> patternTuple, boolean anyGraph,
                                                            Predicate<Tuple<NodeId>> filter, ExecutionContext execCxt) {
        return SolverRX.matchQuadPattern(chain, graphNode, tPattern, nodeTupleTable, patternTuple, anyGraph, filter, execCxt);
    }
}

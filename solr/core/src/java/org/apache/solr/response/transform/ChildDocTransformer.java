/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.response.transform;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.util.BitSet;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.response.DocsStreamer;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrDocumentFetcher;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrReturnFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.response.transform.ChildDocTransformerFactory.NUM_SEP_CHAR;
import static org.apache.solr.response.transform.ChildDocTransformerFactory.PATH_SEP_CHAR;
import static org.apache.solr.schema.IndexSchema.NEST_PATH_FIELD_NAME;

class ChildDocTransformer extends DocTransformer {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String ANON_CHILD_KEY = "_childDocuments_";

  private final String name;
  private final BitSetProducer parentsFilter;
  private final DocSet childDocSet;
  private final int limit; //nocommit how do we handle this?

  private final SolrReturnFields childReturnFields = new SolrReturnFields();

  ChildDocTransformer(String name, BitSetProducer parentsFilter,
                      DocSet childDocSet, int limit) {
    this.name = name;
    this.parentsFilter = parentsFilter;
    this.childDocSet = childDocSet;
    this.limit = limit;
  }

  @Override
  public String getName()  {
    return name;
  }

  @Override
  public void transform(SolrDocument rootDoc, int rootDocId) {
    // note: this algorithm works if both if we have have _nest_path_  and also if we don't!

    try {

      // lookup what the *previous* rootDocId is, and figure which segment this is
      final SolrIndexSearcher searcher = context.getSearcher();
      final List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
      final int seg = ReaderUtil.subIndex(rootDocId, leaves);
      final LeafReaderContext leafReaderContext = leaves.get(seg);
      final int segBaseId = leafReaderContext.docBase;
      final int segRootId = rootDocId - segBaseId;
      final BitSet segParentsBitSet = parentsFilter.getBitSet(leafReaderContext);
      final int segPrevRootId = segParentsBitSet.prevSetBit(segRootId - 1); // can return -1 and that's okay

      // we'll need this soon...
      final SortedDocValues segPathDocValues = DocValues.getSorted(leafReaderContext.reader(), NEST_PATH_FIELD_NAME);

      // the key in the Map is the document's ancestors key(one above the parent), while the key in the intermediate
      // MultiMap is the direct child document's key(of the parent document)
      Map<String, Multimap<String, SolrDocument>> pendingParentPathsToChildren = new HashMap<>();

      IndexSchema schema = searcher.getSchema();
      SolrDocumentFetcher docFetcher = searcher.getDocFetcher();
      Set<String> dvFieldsToReturn = docFetcher.getNonStoredDVs(true);
      boolean shouldDecorateWithDVs = dvFieldsToReturn.size() > 0;

      // Loop each child ID up to the parent (exclusive).
      for (int docId = segBaseId + segPrevRootId + 1; docId < rootDocId; ++docId) {

        // get the path.  (note will default to ANON_CHILD_KEY if not in schema or oddly blank)
        String fullDocPath = getPathByDocId(docId - segBaseId, segPathDocValues);

        // Is this doc a direct ancestor of another doc we've seen?
        boolean isAncestor = pendingParentPathsToChildren.containsKey(fullDocPath);

        // Do we need to do anything with this doc (either ancestor or matched the child query)
        if (isAncestor || childDocSet == null || childDocSet.exists(docId)) {
          // load the doc
          SolrDocument doc = DocsStreamer.convertLuceneDocToSolrDoc(docFetcher.doc(docId), schema, childReturnFields);
          if (shouldDecorateWithDVs) {
            docFetcher.decorateDocValueFields(doc, docId, dvFieldsToReturn);
          }

          if (isAncestor) {
            // if this path has pending child docs, add them.
            addChildrenToParent(doc, pendingParentPathsToChildren.remove(fullDocPath)); // no longer pending
          }
          
          // get parent path
          String parentDocPath = getParentPath(fullDocPath);
          String lastPath = getLastPath(fullDocPath);
          // put into pending:
          // trim path if the doc was inside array, see trimPathIfArrayDoc()
          // e.g. toppings#1/ingredients#1 -> outer map key toppings#1
          // -> inner MultiMap key ingredients
          // or lonely#/lonelyGrandChild# -> outer map key lonely#
          // -> inner MultiMap key lonelyGrandChild#
          pendingParentPathsToChildren.computeIfAbsent(parentDocPath, x -> ArrayListMultimap.create())
              .put(trimLastPoundIfArray(lastPath), doc); // multimap add (won't replace)
        }
      }

      // only children of parent remain
      assert pendingParentPathsToChildren.keySet().size() == 1;

      addChildrenToParent(rootDoc, pendingParentPathsToChildren.remove(null));

    } catch (IOException e) {
      //TODO DWS: reconsider this unusual error handling approach; shouldn't we rethrow?
      log.warn("Could not fetch child documents", e);
      rootDoc.put(getName(), "Could not fetch child documents");
    }
  }

  private static void addChildrenToParent(SolrDocument parent, Multimap<String, SolrDocument> children) {
    for(String childLabel: children.keySet()) {
      addChildrenToParent(parent, children.get(childLabel), childLabel);
    }
  }

  private static void addChildrenToParent(SolrDocument parent, Collection<SolrDocument> children, String cDocsPath) {
    // if no paths; we do not need to add the child document's relation to its parent document.
    if (cDocsPath.equals(ANON_CHILD_KEY)) {
      parent.addChildDocuments(children);
      return;
    }
    // lookup leaf key for these children using path
    // depending on the label, add to the parent at the right key/label
    String trimmedPath = trimLastPound(cDocsPath);
    // if the child doc's path does not end with #, it is an array(same string is returned by ChildDocTransformer#trimLastPound)
    if (!parent.containsKey(trimmedPath) && (trimmedPath == cDocsPath)) {
      List<SolrDocument> list = new ArrayList<>(children);
      parent.setField(trimmedPath, list);
      return;
    }
    // is single value
    parent.setField(trimmedPath, ((List)children).get(0));
  }

  private static String getLastPath(String path) {
    int lastIndexOfPathSepChar = path.lastIndexOf(PATH_SEP_CHAR);
    if(lastIndexOfPathSepChar == -1) {
      return path;
    }
    return path.substring(lastIndexOfPathSepChar + 1);
  }

  private static String trimLastPoundIfArray(String path) {
    // remove index after last pound sign and if there is an array index e.g. toppings#1 -> toppings
    // or return original string if child doc is not in an array ingredients# -> ingredients#
    final int indexOfSepChar = path.lastIndexOf(NUM_SEP_CHAR);
    if (indexOfSepChar == -1) {
      return path;
    }
    int lastIndex = path.length() - 1;
    boolean singleDocVal = indexOfSepChar == lastIndex;
    return singleDocVal ? path: path.substring(0, indexOfSepChar);
  }

  private static String trimLastPound(String path) {
    // remove index after last pound sign and index from e.g. toppings#1 -> toppings
    int lastIndex = path.lastIndexOf('#');
    return lastIndex == -1 ? path : path.substring(0, lastIndex);
  }

  /**
   * Returns the *parent* path for this document.
   * Children of the root will yield null.
   */
  private static String getParentPath(String currDocPath) {
    // chop off leaf (after last '/')
    // if child of leaf then return null (special value)
    int lastPathIndex = currDocPath.lastIndexOf(PATH_SEP_CHAR);
    return lastPathIndex == -1 ? null : currDocPath.substring(0, lastPathIndex);
  }

  /** Looks up the nest path.  If there is none, returns {@link #ANON_CHILD_KEY}. */
  private static String getPathByDocId(int segDocId, SortedDocValues segPathDocValues) throws IOException {
    int numToAdvance = segPathDocValues.docID() == -1 ? segDocId : segDocId - (segPathDocValues.docID());
    assert numToAdvance >= 0;
    boolean advanced = segPathDocValues.advanceExact(segDocId);
    if (!advanced) {
      return ANON_CHILD_KEY;
    }
    return segPathDocValues.binaryValue().utf8ToString();
  }

  private static String getSolrFieldString(Object fieldVal, FieldType fieldType) {
    return fieldVal instanceof IndexableField
        ? fieldType.toExternal((IndexableField)fieldVal)
        : fieldVal.toString();
  }
}
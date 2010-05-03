package org.apache.solr.request;

import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldCache;
import org.apache.lucene.search.Filter;
import org.apache.lucene.util.PriorityQueue;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.FacetParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.schema.FieldType;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.SolrIndexReader;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.util.BoundedTreeSet;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;


class PerSegmentSingleValuedFaceting {

  // input params
  SolrIndexSearcher searcher;
  DocSet docs;
  String fieldName;
  int offset;
  int limit;
  int mincount;
  boolean missing;
  String sort;
  String prefix;

  Filter baseSet;

  int nThreads;

  public PerSegmentSingleValuedFaceting(SolrIndexSearcher searcher, DocSet docs, String fieldName, int offset, int limit, int mincount, boolean missing, String sort, String prefix) {
    this.searcher = searcher;
    this.docs = docs;
    this.fieldName = fieldName;
    this.offset = offset;
    this.limit = limit;
    this.mincount = mincount;
    this.missing = missing;
    this.sort = sort;
    this.prefix = prefix;
  }

  public void setNumThreads(int threads) {
    nThreads = threads;
  }


  NamedList getFacetCounts(Executor executor) throws IOException {

    CompletionService<SegFacet> completionService = new ExecutorCompletionService<SegFacet>(executor);

    // reuse the translation logic to go from top level set to per-segment set
    baseSet = docs.getTopFilter();

    SolrIndexReader topReader = searcher.getReader();
    final SolrIndexReader[] leafReaders = topReader.getLeafReaders();
    int[] offsets = topReader.getLeafOffsets();

    // The list of pending tasks that aren't immediately submitted
    // TODO: Is there a completion service, or a delegating executor that can
    // limit the number of concurrent tasks submitted to a bigger executor?
    LinkedList<Callable<SegFacet>> pending = new LinkedList<Callable<SegFacet>>();

    int threads = nThreads <= 0 ? Integer.MAX_VALUE : nThreads;

    for (int i=0; i<leafReaders.length; i++) {
      final SegFacet segFacet = new SegFacet(leafReaders[i], offsets[i]);

      Callable<SegFacet> task = new Callable<SegFacet>() {
        public SegFacet call() throws Exception {
          segFacet.countTerms();
          return segFacet;
        }
      };

      // TODO: if limiting threads, submit by largest segment first?

      if (--threads >= 0) {
        completionService.submit(task);
      } else {
        pending.add(task);
      }
    }


    // now merge the per-segment results
    PriorityQueue<SegFacet> queue = new PriorityQueue<SegFacet>() {
      {
        initialize(leafReaders.length);
      }
      @Override
      protected boolean lessThan(SegFacet a, SegFacet b) {
        return a.terms[a.pos].compareTo(b.terms[b.pos]) < 0;
      }
    };


    boolean hasMissingCount=false;
    int missingCount=0;
    for (int i=0; i<leafReaders.length; i++) {
      SegFacet seg = null;

      try {
        Future<SegFacet> future = completionService.take();        
        seg = future.get();
        if (!pending.isEmpty()) {
          completionService.submit(pending.removeFirst());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
          throw (RuntimeException)cause;
        } else {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error in per-segment faceting on field: " + fieldName, cause);
        }
      }


      if (seg.startTermIndex < seg.endTermIndex) {
        if (seg.startTermIndex==0) {
          hasMissingCount=true;
          missingCount += seg.counts[0];
          seg.pos = 1;
        } else {
          seg.pos = seg.startTermIndex;
        }
        if (seg.pos < seg.endTermIndex) {
          queue.add(seg);
        }
      }
    }

    FacetCollector collector;
    if (sort.equals(FacetParams.FACET_SORT_COUNT) || sort.equals(FacetParams.FACET_SORT_COUNT_LEGACY)) {
      collector = new CountSortedFacetCollector(offset, limit, mincount);
    } else {
      collector = new IndexSortedFacetCollector(offset, limit, mincount);
    }

    while (queue.size() > 0) {
      SegFacet seg = queue.top();
      String val = seg.terms[seg.pos];
      int count = 0;

      do {
        count += seg.counts[seg.pos - seg.startTermIndex];

        // TODO: OPTIMIZATION...
        // if mincount>0 then seg.pos++ can skip ahead to the next non-zero entry.
        seg.pos++;
        if (seg.pos >= seg.endTermIndex) {
          queue.pop();
          seg = queue.top();
        }  else {
          seg = queue.updateTop();
        }
      } while (seg != null && val.compareTo(seg.terms[seg.pos]) == 0);

      boolean stop = collector.collect(val, count);
      if (stop) break;
    }

    NamedList res = collector.getFacetCounts();

    // convert labels to readable form    
    FieldType ft = searcher.getSchema().getFieldType(fieldName);
    int sz = res.size();
    for (int i=0; i<sz; i++) {
      res.setName(i, ft.indexedToReadable(res.getName(i)));
    }

    if (missing) {
      if (!hasMissingCount) {
        missingCount = SimpleFacets.getFieldMissingCount(searcher,docs,fieldName);
      }
      res.add(null, missingCount);
    }

    return res;
  }





    // first element of the fieldcache is null, so we need this comparator.
  private static final Comparator nullStrComparator = new Comparator() {
        public int compare(Object o1, Object o2) {
          if (o1==null) return (o2==null) ? 0 : -1;
          else if (o2==null) return 1;
          return ((String)o1).compareTo((String)o2);
        }
      };
  

  class SegFacet {
    SolrIndexReader reader;
    int readerOffset;

    SegFacet(SolrIndexReader reader, int readerOffset) {
      this.reader = reader;
      this.readerOffset = readerOffset;
    }
    
    int[] ords;
    String[] terms;

    int startTermIndex;
    int endTermIndex;
    int[] counts;

    int pos; // only used during merge with other segments

    void countTerms() throws IOException {
      FieldCache.StringIndex si = FieldCache.DEFAULT.getStringIndex(reader, fieldName);
      final String[] terms = this.terms = si.lookup;
      final int[] termNum = this.ords = si.order;
      // SolrCore.log.info("reader= " + reader + "  FC=" + System.identityHashCode(si));

      if (prefix!=null) {
        startTermIndex = Arrays.binarySearch(terms,prefix,nullStrComparator);
        if (startTermIndex<0) startTermIndex=-startTermIndex-1;
        // find the end term.  \uffff isn't a legal unicode char, but only compareTo
        // is used, so it should be fine, and is guaranteed to be bigger than legal chars.
        // TODO: switch to binarySearch version that takes start/end in Java6
        endTermIndex = Arrays.binarySearch(terms,prefix+"\uffff\uffff\uffff\uffff",nullStrComparator);
        endTermIndex = -endTermIndex-1;
      } else {
        startTermIndex=0;
        endTermIndex=terms.length;
      }

      final int nTerms=endTermIndex-startTermIndex;
      if (nTerms>0) {
        // count collection array only needs to be as big as the number of terms we are
        // going to collect counts for.
        final int[] counts = this.counts = new int[nTerms];
        DocIdSet idSet = baseSet.getDocIdSet(reader);
        DocIdSetIterator iter = idSet.iterator();

        if (startTermIndex==0 && endTermIndex==terms.length) {
          // specialized version when collecting counts for all terms
          int doc;
          while ((doc = iter.nextDoc()) < DocIdSetIterator.NO_MORE_DOCS) {
            counts[termNum[doc]]++;
          }
        } else {
          // version that adjusts term numbers because we aren't collecting the full range
          int doc;
          while ((doc = iter.nextDoc()) < DocIdSetIterator.NO_MORE_DOCS) {
            int term = termNum[doc];
            int arrIdx = term-startTermIndex;
            if (arrIdx>=0 && arrIdx<nTerms) counts[arrIdx]++;
          }
        }
      }
    }
  }

}



abstract class FacetCollector {
  /*** return true to stop collection */
  public abstract boolean collect(String term, int count);
  public abstract NamedList getFacetCounts();
}


// This collector expects facets to be collected in index order
class CountSortedFacetCollector extends FacetCollector {
  final int offset;
  final int limit;
  final int maxsize;
  final BoundedTreeSet<SimpleFacets.CountPair<String,Integer>> queue;

  int min;  // the smallest value in the top 'N' values

  public CountSortedFacetCollector(int offset, int limit, int mincount) {
    this.offset = offset;
    this.limit = limit;
    maxsize = limit>0 ? offset+limit : Integer.MAX_VALUE-1;
    queue = new BoundedTreeSet<SimpleFacets.CountPair<String,Integer>>(maxsize);
    min=mincount-1;  // the smallest value in the top 'N' values
  }

  @Override
  public boolean collect(String term, int count) {
    if (count > min) {
      // NOTE: we use c>min rather than c>=min as an optimization because we are going in
      // index order, so we already know that the keys are ordered.  This can be very
      // important if a lot of the counts are repeated (like zero counts would be).
      queue.add(new SimpleFacets.CountPair<String,Integer>(term, count));
      if (queue.size()>=maxsize) min=queue.last().val;
    }
    return false;
  }

  @Override
  public NamedList getFacetCounts() {
    NamedList res = new NamedList();
    int off=offset;
    int lim=limit>=0 ? limit : Integer.MAX_VALUE;
     // now select the right page from the results
     for (SimpleFacets.CountPair<String,Integer> p : queue) {
       if (--off>=0) continue;
       if (--lim<0) break;
       res.add(p.key, p.val);
     }
    return res;
  }
}

// This collector expects facets to be collected in index order
class IndexSortedFacetCollector extends FacetCollector {
  int offset;
  int limit;
  final int mincount;
  final NamedList res = new NamedList();


  public IndexSortedFacetCollector(int offset, int limit, int mincount) {
    this.offset = offset;
    this.limit = limit>0 ? limit : Integer.MAX_VALUE;
    this.mincount = mincount;
  }

  @Override
  public boolean collect(String term, int count) {
    if (count < mincount) {
      return false;
    }

    if (offset > 0) {
      offset--;
      return false;
    }

    if (limit > 0) {
      res.add(term, count);
      limit--;
    }

    return limit <= 0;
  }

  @Override
  public NamedList getFacetCounts() {
    return res;
  }
}
package com.github.saaay71.solr.query;

import com.github.saaay71.solr.VectorUtils;
import org.apache.lucene.index.*;
import org.apache.lucene.search.DoubleValues;
import org.apache.lucene.search.DoubleValuesSource;
import org.apache.lucene.search.IndexSearcher;
import org.apache.solr.common.SolrException;

import java.io.IOException;
import java.util.*;

public class VectorValuesSource extends DoubleValuesSource {
    private final String field;
    private List<Double> queryVector;
    private double queryVectorNorm;
    private boolean cosine;

    private double[] docVector;

    public VectorValuesSource(String field, String queryVector, boolean cosine) {
        this.field = field;
        this.queryVector = new ArrayList<>();
        this.cosine = cosine;
        String[] vectorArray = queryVector.split(",");
        for (String s : vectorArray) {
            double v = Double.parseDouble(s);
            this.queryVector.add(v);
            if (cosine) {
                queryVectorNorm += Math.pow(v, 2.0);
            }
        }
    }

    public DoubleValues getValues(LeafReaderContext leafReaderContext, DoubleValues doubleValues) throws IOException {

        LeafReader reader = leafReaderContext.reader();

        return new DoubleValues() {

            public double doubleValue() throws IOException {
                double docVectorNorm = 0.0;
                double score = 0;
                int vectorIndex = 0;
                for (double docDim : docVector) {
                    Double vecDim = queryVector.get(vectorIndex);
                    vectorIndex++;
                    if (cosine)
                        docVectorNorm += Math.pow(docDim, 2.0);

                    score = (score + vecDim * docDim);
                }

                if (cosine) {
                    if ((docVectorNorm == 0) || (queryVectorNorm == 0)) score = 0f;
                    score = (float) (score / (Math.sqrt(docVectorNorm) * Math.sqrt(queryVectorNorm)));
                }
                return score;
            }

            public boolean advanceExact(int doc) throws IOException {
                Set<String> fields = new HashSet<String>() {{
                    add(field);
                }};
                /**BytesRef vecBytes = reader.document(doc, fields).getBinaryValue(field);
                 if (vecBytes == null) {
                 return false;
                 }
                 docVector = VectorUtils.decodeDense(vecBytes);
                 return true;*/
                String docVectorString = reader.document(doc, fields).get(field);
                if (docVectorString == null) {
                    throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Parse document vector failure.");
                }
                docVector = VectorUtils.parseVectorStringDense(docVectorString);
                return true;
            }
        };
    }

    public boolean needsScores() {
        return true;
    }

    public DoubleValuesSource rewrite(IndexSearcher indexSearcher) throws IOException {
        return this;
    }

    public int hashCode() {
        return 0;
    }

    public boolean equals(Object o) {
        return false;
    }

    public String toString() {
        return cosine ? "cosine(" + field + ", doc)" : "dot-product(" + field + ", doc)";
    }

    public boolean isCacheable(LeafReaderContext leafReaderContext) {
        return false;
    }
}

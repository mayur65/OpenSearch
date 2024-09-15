/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.index.compositeindex.datacube.startree.builder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.EmptyDocValuesProducer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedNumericDocValuesWriterWrapper;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.Counter;
import org.apache.lucene.util.NumericUtils;
import org.opensearch.index.compositeindex.datacube.Dimension;
import org.opensearch.index.compositeindex.datacube.Metric;
import org.opensearch.index.compositeindex.datacube.MetricStat;
import org.opensearch.index.compositeindex.datacube.startree.StarTreeDocument;
import org.opensearch.index.compositeindex.datacube.startree.StarTreeField;
import org.opensearch.index.compositeindex.datacube.startree.StarTreeFieldConfiguration;
import org.opensearch.index.compositeindex.datacube.startree.aggregators.MetricAggregatorInfo;
import org.opensearch.index.compositeindex.datacube.startree.aggregators.ValueAggregator;
import org.opensearch.index.compositeindex.datacube.startree.fileformats.StarTreeWriter;
import org.opensearch.index.compositeindex.datacube.startree.index.StarTreeValues;
import org.opensearch.index.compositeindex.datacube.startree.node.InMemoryTreeNode;
import org.opensearch.index.compositeindex.datacube.startree.node.StarTreeNodeType;
import org.opensearch.index.compositeindex.datacube.startree.utils.SequentialDocValuesIterator;
import org.opensearch.index.mapper.DocCountFieldMapper;
import org.opensearch.index.mapper.FieldMapper;
import org.opensearch.index.mapper.FieldValueConverter;
import org.opensearch.index.mapper.Mapper;
import org.opensearch.index.mapper.MapperService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opensearch.index.compositeindex.datacube.startree.utils.StarTreeUtils.ALL;
import static org.opensearch.index.compositeindex.datacube.startree.utils.StarTreeUtils.fullyQualifiedFieldNameForStarTreeDimensionsDocValues;
import static org.opensearch.index.compositeindex.datacube.startree.utils.StarTreeUtils.fullyQualifiedFieldNameForStarTreeMetricsDocValues;
import static org.opensearch.index.compositeindex.datacube.startree.utils.StarTreeUtils.getFieldInfo;
import static org.opensearch.index.mapper.NumberFieldMapper.NumberType.DOUBLE;
import static org.opensearch.index.mapper.NumberFieldMapper.NumberType.LONG;

/**
 * Builder for star tree. Defines the algorithm to construct star-tree
 * See {@link StarTreesBuilder} for information around the construction of star-trees based on star-tree fields
 *
 * @opensearch.experimental
 */
public abstract class BaseStarTreeBuilder implements StarTreeBuilder {

    private static final Logger logger = LogManager.getLogger(BaseStarTreeBuilder.class);

    /**
     * Default value for star node
     */
    public static final Long STAR_IN_DOC_VALUES_INDEX = null;
    protected final Set<Integer> skipStarNodeCreationForDimensions;

    protected final List<MetricAggregatorInfo> metricAggregatorInfos;
    protected final int numMetrics;
    protected final int numDimensions;
    protected int numStarTreeDocs;
    protected int totalSegmentDocs;
    protected int numStarTreeNodes;
    protected final int maxLeafDocuments;

    protected final InMemoryTreeNode rootNode = getNewNode();

    protected final StarTreeField starTreeField;
    private final SegmentWriteState writeState;

    private final IndexOutput metaOut;
    private final IndexOutput dataOut;

    /**
     * Reads all the configuration related to dimensions and metrics, builds a star-tree based on the different construction parameters.
     *
     * @param starTreeField holds the configuration for the star tree
     * @param writeState    stores the segment write writeState
     * @param mapperService helps to find the original type of the field
     */
    protected BaseStarTreeBuilder(
        IndexOutput metaOut,
        IndexOutput dataOut,
        StarTreeField starTreeField,
        SegmentWriteState writeState,
        MapperService mapperService
    ) {
        logger.debug("Building star tree : {}", starTreeField.getName());

        this.metaOut = metaOut;
        this.dataOut = dataOut;

        this.starTreeField = starTreeField;
        StarTreeFieldConfiguration starTreeFieldSpec = starTreeField.getStarTreeConfig();

        List<Dimension> dimensionsSplitOrder = starTreeField.getDimensionsOrder();
        this.numDimensions = dimensionsSplitOrder.size();

        this.skipStarNodeCreationForDimensions = new HashSet<>();
        this.totalSegmentDocs = writeState.segmentInfo.maxDoc();
        this.writeState = writeState;

        Set<String> skipStarNodeCreationForDimensions = starTreeFieldSpec.getSkipStarNodeCreationInDims();

        for (int i = 0; i < numDimensions; i++) {
            if (skipStarNodeCreationForDimensions.contains(dimensionsSplitOrder.get(i).getField())) {
                this.skipStarNodeCreationForDimensions.add(i);
            }
        }

        this.metricAggregatorInfos = generateMetricAggregatorInfos(mapperService);
        this.numMetrics = metricAggregatorInfos.size();
        this.maxLeafDocuments = starTreeFieldSpec.maxLeafDocs();
    }

    /**
     * Generates the configuration required to perform aggregation for all the metrics on a field
     *
     * @return list of MetricAggregatorInfo
     */
    public List<MetricAggregatorInfo> generateMetricAggregatorInfos(MapperService mapperService) {
        List<MetricAggregatorInfo> metricAggregatorInfos = new ArrayList<>();
        for (Metric metric : this.starTreeField.getMetrics()) {
            if (metric.getField().equals(DocCountFieldMapper.NAME)) {
                MetricAggregatorInfo metricAggregatorInfo = new MetricAggregatorInfo(
                    MetricStat.DOC_COUNT,
                    metric.getField(),
                    starTreeField.getName(),
                    LONG
                );
                metricAggregatorInfos.add(metricAggregatorInfo);
                continue;
            }
            for (MetricStat metricStat : metric.getBaseMetrics()) {
                FieldValueConverter fieldValueConverter;
                Mapper fieldMapper = mapperService.documentMapper().mappers().getMapper(metric.getField());
                if (fieldMapper instanceof FieldMapper && ((FieldMapper) fieldMapper).fieldType() instanceof FieldValueConverter) {
                    fieldValueConverter = (FieldValueConverter) ((FieldMapper) fieldMapper).fieldType();
                } else {
                    logger.error("unsupported mapper type");
                    throw new IllegalStateException("unsupported mapper type");
                }

                MetricAggregatorInfo metricAggregatorInfo = new MetricAggregatorInfo(
                    metricStat,
                    metric.getField(),
                    starTreeField.getName(),
                    fieldValueConverter
                );
                metricAggregatorInfos.add(metricAggregatorInfo);
            }
        }
        return metricAggregatorInfos;
    }

    /**
     * Generates the configuration required to perform aggregation for all the metrics on a field
     *
     * @return list of MetricAggregatorInfo
     */
    public List<SequentialDocValuesIterator> getMetricReaders(SegmentWriteState state, Map<String, DocValuesProducer> fieldProducerMap)
        throws IOException {

        List<SequentialDocValuesIterator> metricReaders = new ArrayList<>();
        for (Metric metric : this.starTreeField.getMetrics()) {
            for (MetricStat metricStat : metric.getBaseMetrics()) {
                SequentialDocValuesIterator metricReader;
                FieldInfo metricFieldInfo = state.fieldInfos.fieldInfo(metric.getField());
                if (metricStat.equals(MetricStat.DOC_COUNT)) {
                    // _doc_count is numeric field , so we convert to sortedNumericDocValues and get iterator
                    metricReader = getIteratorForNumericField(fieldProducerMap, metricFieldInfo, DocCountFieldMapper.NAME);
                } else {
                    if (metricFieldInfo == null) {
                        metricFieldInfo = getFieldInfo(metric.getField(), DocValuesType.SORTED_NUMERIC);
                    }
                    metricReader = new SequentialDocValuesIterator(
                        fieldProducerMap.get(metricFieldInfo.name).getSortedNumeric(metricFieldInfo)
                    );
                }
                metricReaders.add(metricReader);
            }
        }
        return metricReaders;
    }

    /**
     * Builds the star tree from the original segment documents
     *
     * @param fieldProducerMap           contain s the docValues producer to get docValues associated with each field
     * @param fieldNumberAcrossStarTrees maintains a counter for the number of star-tree fields
     * @param starTreeDocValuesConsumer  consumes the generated star-tree docValues
     * @throws IOException when we are unable to build star-tree
     */
    public void build(
        Map<String, DocValuesProducer> fieldProducerMap,
        AtomicInteger fieldNumberAcrossStarTrees,
        DocValuesConsumer starTreeDocValuesConsumer
    ) throws IOException {
        long startTime = System.currentTimeMillis();
        logger.debug("Star-tree build is a go with star tree field {}", starTreeField.getName());

        List<SequentialDocValuesIterator> metricReaders = getMetricReaders(writeState, fieldProducerMap);
        List<Dimension> dimensionsSplitOrder = starTreeField.getDimensionsOrder();
        SequentialDocValuesIterator[] dimensionReaders = new SequentialDocValuesIterator[dimensionsSplitOrder.size()];
        for (int i = 0; i < numDimensions; i++) {
            String dimension = dimensionsSplitOrder.get(i).getField();
            FieldInfo dimensionFieldInfo = writeState.fieldInfos.fieldInfo(dimension);
            if (dimensionFieldInfo == null) {
                dimensionFieldInfo = getFieldInfo(dimension, DocValuesType.SORTED_NUMERIC);
            }
            dimensionReaders[i] = new SequentialDocValuesIterator(
                fieldProducerMap.get(dimensionFieldInfo.name).getSortedNumeric(dimensionFieldInfo)
            );
        }
        Iterator<StarTreeDocument> starTreeDocumentIterator = sortAndAggregateSegmentDocuments(dimensionReaders, metricReaders);
        logger.debug("Sorting and aggregating star-tree in ms : {}", (System.currentTimeMillis() - startTime));
        build(starTreeDocumentIterator, fieldNumberAcrossStarTrees, starTreeDocValuesConsumer);
        logger.debug("Finished Building star-tree in ms : {}", (System.currentTimeMillis() - startTime));
    }

    /**
     * Builds the star tree using sorted and aggregated star-tree Documents
     *
     * @param starTreeDocumentIterator   contains the sorted and aggregated documents
     * @param fieldNumberAcrossStarTrees maintains a counter for the number of star-tree fields
     * @param starTreeDocValuesConsumer  consumes the generated star-tree docValues
     * @throws IOException when we are unable to build star-tree
     */
    public void build(
        Iterator<StarTreeDocument> starTreeDocumentIterator,
        AtomicInteger fieldNumberAcrossStarTrees,
        DocValuesConsumer starTreeDocValuesConsumer
    ) throws IOException {
        int numSegmentStarTreeDocument = totalSegmentDocs;

        appendDocumentsToStarTree(starTreeDocumentIterator);
        int numStarTreeDocument = numStarTreeDocs;
        logger.debug("Generated star tree docs : [{}] from segment docs : [{}]", numStarTreeDocument, numSegmentStarTreeDocument);

        if (numStarTreeDocs == 0) {
            // serialize the star tree data
            serializeStarTree(numStarTreeDocument, numStarTreeDocs);
            return;
        }

        constructStarTree(rootNode, 0, numStarTreeDocs);
        int numStarTreeDocumentUnderStarNode = numStarTreeDocs - numStarTreeDocument;
        logger.debug(
            "Finished constructing star-tree, got [ {} ] tree nodes and [ {} ] starTreeDocument under star-node",
            numStarTreeNodes,
            numStarTreeDocumentUnderStarNode
        );

        createAggregatedDocs(rootNode);
        int numAggregatedStarTreeDocument = numStarTreeDocs - numStarTreeDocument - numStarTreeDocumentUnderStarNode;
        logger.debug("Finished creating aggregated documents : {}", numAggregatedStarTreeDocument);

        // Create doc values indices in disk
        createSortedDocValuesIndices(starTreeDocValuesConsumer, fieldNumberAcrossStarTrees);

        // serialize star-tree
        serializeStarTree(numStarTreeDocument, numStarTreeDocs);
    }

    void appendDocumentsToStarTree(Iterator<StarTreeDocument> starTreeDocumentIterator) throws IOException {
        while (starTreeDocumentIterator.hasNext()) {
            appendToStarTree(starTreeDocumentIterator.next());
        }
    }

    private void serializeStarTree(int numSegmentStarTreeDocument, int numStarTreeDocs) throws IOException {
        // serialize the star tree data
        long dataFilePointer = dataOut.getFilePointer();
        StarTreeWriter starTreeWriter = new StarTreeWriter();
        long totalStarTreeDataLength = starTreeWriter.writeStarTree(dataOut, rootNode, numStarTreeNodes, starTreeField.getName());

        // serialize the star tree meta
        starTreeWriter.writeStarTreeMetadata(
            metaOut,
            starTreeField,
            metricAggregatorInfos,
            numStarTreeNodes,
            numSegmentStarTreeDocument,
            numStarTreeDocs,
            dataFilePointer,
            totalStarTreeDataLength
        );
    }

    private void createSortedDocValuesIndices(DocValuesConsumer docValuesConsumer, AtomicInteger fieldNumberAcrossStarTrees)
        throws IOException {
        List<SortedNumericDocValuesWriterWrapper> dimensionWriters = new ArrayList<>();
        List<SortedNumericDocValuesWriterWrapper> metricWriters = new ArrayList<>();
        FieldInfo[] dimensionFieldInfoList = new FieldInfo[starTreeField.getDimensionsOrder().size()];
        FieldInfo[] metricFieldInfoList = new FieldInfo[metricAggregatorInfos.size()];
        for (int i = 0; i < dimensionFieldInfoList.length; i++) {
            final FieldInfo fi = getFieldInfo(
                fullyQualifiedFieldNameForStarTreeDimensionsDocValues(
                    starTreeField.getName(),
                    starTreeField.getDimensionsOrder().get(i).getField()
                ),
                DocValuesType.SORTED_NUMERIC,
                fieldNumberAcrossStarTrees.getAndIncrement()
            );
            dimensionFieldInfoList[i] = fi;
            dimensionWriters.add(new SortedNumericDocValuesWriterWrapper(fi, Counter.newCounter()));
        }
        for (int i = 0; i < metricAggregatorInfos.size(); i++) {

            final FieldInfo fi = getFieldInfo(
                fullyQualifiedFieldNameForStarTreeMetricsDocValues(
                    starTreeField.getName(),
                    metricAggregatorInfos.get(i).getField(),
                    metricAggregatorInfos.get(i).getMetricStat().getTypeName()
                ),
                DocValuesType.SORTED_NUMERIC,
                fieldNumberAcrossStarTrees.getAndIncrement()
            );

            metricFieldInfoList[i] = fi;
            metricWriters.add(new SortedNumericDocValuesWriterWrapper(fi, Counter.newCounter()));
        }

        for (int docId = 0; docId < numStarTreeDocs; docId++) {
            StarTreeDocument starTreeDocument = getStarTreeDocument(docId);
            for (int i = 0; i < starTreeDocument.dimensions.length; i++) {
                if (starTreeDocument.dimensions[i] != null) {
                    dimensionWriters.get(i).addValue(docId, starTreeDocument.dimensions[i]);
                }
            }

            for (int i = 0; i < starTreeDocument.metrics.length; i++) {
                try {
                    FieldValueConverter aggregatedValueType = metricAggregatorInfos.get(i).getValueAggregators().getAggregatedValueType();
                    if (aggregatedValueType.equals(LONG)) {
                        if (starTreeDocument.metrics[i] != null) {
                            metricWriters.get(i).addValue(docId, (long) starTreeDocument.metrics[i]);
                        }
                    } else if (aggregatedValueType.equals(DOUBLE)) {
                        if (starTreeDocument.metrics[i] != null) {
                            metricWriters.get(i).addValue(docId, NumericUtils.doubleToSortableLong((Double) starTreeDocument.metrics[i]));
                        }
                    } else {
                        throw new IllegalStateException("Unknown metric doc value type");
                    }
                } catch (IllegalArgumentException e) {
                    logger.error("could not parse the value, exiting creation of star tree");
                }
            }
        }

        addStarTreeDocValueFields(docValuesConsumer, dimensionWriters, dimensionFieldInfoList, starTreeField.getDimensionsOrder().size());
        addStarTreeDocValueFields(docValuesConsumer, metricWriters, metricFieldInfoList, metricAggregatorInfos.size());
    }

    private void addStarTreeDocValueFields(
        DocValuesConsumer docValuesConsumer,
        List<SortedNumericDocValuesWriterWrapper> docValuesWriters,
        FieldInfo[] fieldInfoList,
        int fieldCount
    ) throws IOException {
        for (int i = 0; i < fieldCount; i++) {
            final int writerIndex = i;
            DocValuesProducer docValuesProducer = new EmptyDocValuesProducer() {
                @Override
                public SortedNumericDocValues getSortedNumeric(FieldInfo field) {
                    return docValuesWriters.get(writerIndex).getDocValues();
                }
            };
            docValuesConsumer.addSortedNumericField(fieldInfoList[i], docValuesProducer);
        }
    }

    /**
     * Get star tree document from the segment for the current docId with the dimensionReaders and metricReaders
     */
    protected StarTreeDocument getStarTreeDocument(
        int currentDocId,
        SequentialDocValuesIterator[] dimensionReaders,
        List<SequentialDocValuesIterator> metricReaders
    ) throws IOException {
        Long[] dims = new Long[numDimensions];
        int i = 0;
        for (SequentialDocValuesIterator dimensionDocValueIterator : dimensionReaders) {
            dimensionDocValueIterator.nextDoc(currentDocId);
            Long val = dimensionDocValueIterator.value(currentDocId);
            dims[i] = val;
            i++;
        }
        i = 0;
        Object[] metrics = new Object[metricReaders.size()];
        for (SequentialDocValuesIterator metricDocValuesIterator : metricReaders) {
            metricDocValuesIterator.nextDoc(currentDocId);
            // As part of merge, we traverse the star tree doc values
            // The type of data stored in metric fields is different from the
            // actual indexing field they're based on
            metrics[i] = metricAggregatorInfos.get(i)
                .getValueAggregators()
                .toAggregatedValueType(metricDocValuesIterator.value(currentDocId));
            i++;
        }
        return new StarTreeDocument(dims, metrics);
    }

    /**
     * Adds a document to the star-tree.
     *
     * @param starTreeDocument star tree document to be added
     * @throws IOException if an I/O error occurs while adding the document
     */
    public abstract void appendStarTreeDocument(StarTreeDocument starTreeDocument) throws IOException;

    /**
     * Returns the document of the given document id in the star-tree.
     *
     * @param docId document id
     * @return star tree document
     * @throws IOException if an I/O error occurs while fetching the star-tree document
     */
    public abstract StarTreeDocument getStarTreeDocument(int docId) throws IOException;

    /**
     * Retrieves the list of star-tree documents in the star-tree.
     *
     * @return Star tree documents
     */
    public abstract List<StarTreeDocument> getStarTreeDocuments() throws IOException;

    /**
     * Returns the value of the dimension for the given dimension id and document in the star-tree.
     *
     * @param docId       document id
     * @param dimensionId dimension id
     * @return dimension value
     */
    public abstract Long getDimensionValue(int docId, int dimensionId) throws IOException;

    /**
     * Sorts and aggregates all the documents in the segment as per the configuration, and returns a star-tree document iterator for all the
     * aggregated star-tree documents.
     *
     * @param dimensionReaders List of docValues readers to read dimensions from the segment
     * @param metricReaders    List of docValues readers to read metrics from the segment
     * @return Iterator for the aggregated star-tree document
     */
    public abstract Iterator<StarTreeDocument> sortAndAggregateSegmentDocuments(
        SequentialDocValuesIterator[] dimensionReaders,
        List<SequentialDocValuesIterator> metricReaders
    ) throws IOException;

    /**
     * Generates aggregated star-tree documents for star-node.
     *
     * @param startDocId  start document id (inclusive) in the star-tree
     * @param endDocId    end document id (exclusive) in the star-tree
     * @param dimensionId dimension id of the star-node
     * @return Iterator for the aggregated star-tree documents
     */
    public abstract Iterator<StarTreeDocument> generateStarTreeDocumentsForStarNode(int startDocId, int endDocId, int dimensionId)
        throws IOException;

    /**
     * Returns the star-tree document from the segment based on the current doc id
     */
    protected StarTreeDocument getSegmentStarTreeDocument(
        int currentDocId,
        SequentialDocValuesIterator[] dimensionReaders,
        List<SequentialDocValuesIterator> metricReaders
    ) throws IOException {
        Long[] dimensions = getStarTreeDimensionsFromSegment(currentDocId, dimensionReaders);
        Object[] metrics = getStarTreeMetricsFromSegment(currentDocId, metricReaders);
        return new StarTreeDocument(dimensions, metrics);
    }

    /**
     * Returns the dimension values for the next document from the segment
     *
     * @return dimension values for each of the star-tree dimension
     * @throws IOException when we are unable to iterate to the next doc for the given dimension readers
     */
    Long[] getStarTreeDimensionsFromSegment(int currentDocId, SequentialDocValuesIterator[] dimensionReaders) throws IOException {
        Long[] dimensions = new Long[numDimensions];
        for (int i = 0; i < numDimensions; i++) {
            if (dimensionReaders[i] != null) {
                try {
                    dimensionReaders[i].nextDoc(currentDocId);
                } catch (IOException e) {
                    logger.error("unable to iterate to next doc", e);
                    throw new RuntimeException("unable to iterate to next doc", e);
                } catch (Exception e) {
                    logger.error("unable to read the dimension values from the segment", e);
                    throw new IllegalStateException("unable to read the dimension values from the segment", e);
                }
                dimensions[i] = dimensionReaders[i].value(currentDocId);
            } else {
                throw new IllegalStateException("dimension readers are empty");
            }
        }
        return dimensions;
    }

    /**
     * Returns the metric values for the next document from the segment
     *
     * @return metric values for each of the star-tree metric
     * @throws IOException when we are unable to iterate to the next doc for the given metric readers
     */
    private Object[] getStarTreeMetricsFromSegment(int currentDocId, List<SequentialDocValuesIterator> metricsReaders) throws IOException {
        Object[] metrics = new Object[numMetrics];
        for (int i = 0; i < numMetrics; i++) {
            SequentialDocValuesIterator metricStatReader = metricsReaders.get(i);
            if (metricStatReader != null) {
                try {
                    metricStatReader.nextDoc(currentDocId);
                } catch (IOException e) {
                    logger.error("unable to iterate to next doc", e);
                    throw new RuntimeException("unable to iterate to next doc", e);
                } catch (Exception e) {
                    logger.error("unable to read the metric values from the segment", e);
                    throw new IllegalStateException("unable to read the metric values from the segment", e);
                }
                metrics[i] = metricStatReader.value(currentDocId);
            } else {
                throw new IllegalStateException("metric readers are empty");
            }
        }
        return metrics;
    }

    /**
     * Merges a star-tree document from the segment into an aggregated star-tree document.
     * A new aggregated star-tree document is created if the aggregated segment document is null.
     *
     * @param aggregatedSegmentDocument aggregated star-tree document
     * @param segmentDocument           segment star-tree document
     * @return merged star-tree document
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected StarTreeDocument reduceSegmentStarTreeDocuments(
        StarTreeDocument aggregatedSegmentDocument,
        StarTreeDocument segmentDocument,
        boolean isMerge
    ) {
        if (aggregatedSegmentDocument == null) {
            Long[] dimensions = Arrays.copyOf(segmentDocument.dimensions, numDimensions);
            Object[] metrics = new Object[numMetrics];
            for (int i = 0; i < numMetrics; i++) {
                try {
                    ValueAggregator metricValueAggregator = metricAggregatorInfos.get(i).getValueAggregators();
                    if (isMerge) {
                        metrics[i] = metricValueAggregator.getInitialAggregatedValue(segmentDocument.metrics[i]);
                    } else {
                        metrics[i] = metricValueAggregator.getInitialAggregatedValueForSegmentDocValue(getLong(segmentDocument.metrics[i]));
                    }

                } catch (Exception e) {
                    logger.error("Cannot parse initial segment doc value", e);
                    throw new IllegalStateException("Cannot parse initial segment doc value [" + segmentDocument.metrics[i] + "]");
                }
            }
            return new StarTreeDocument(dimensions, metrics);
        } else {
            for (int i = 0; i < numMetrics; i++) {
                try {
                    ValueAggregator metricValueAggregator = metricAggregatorInfos.get(i).getValueAggregators();
                    if (isMerge) {
                        aggregatedSegmentDocument.metrics[i] = metricValueAggregator.mergeAggregatedValues(
                            segmentDocument.metrics[i],
                            aggregatedSegmentDocument.metrics[i]
                        );
                    } else {
                        aggregatedSegmentDocument.metrics[i] = metricValueAggregator.mergeAggregatedValueAndSegmentValue(
                            aggregatedSegmentDocument.metrics[i],
                            getLong(segmentDocument.metrics[i])
                        );
                    }
                } catch (Exception e) {
                    logger.error("Cannot apply segment doc value for aggregation", e);
                    throw new IllegalStateException("Cannot apply segment doc value for aggregation [" + segmentDocument.metrics[i] + "]");
                }
            }
            return aggregatedSegmentDocument;
        }
    }

    /**
     * Safely converts the metric value of object type to long.
     * Nulls are handled during aggregation
     *
     * @param metric value of the metric
     * @return converted metric value to long
     */
    private static Long getLong(Object metric) {
        Long metricValue = null;

        if (metric instanceof Long) {
            metricValue = (long) metric;
        }
        return metricValue;
    }

    /**
     * Merges a star-tree document into an aggregated star-tree document.
     * A new aggregated star-tree document is created if the aggregated document is null.
     *
     * @param aggregatedDocument aggregated star-tree document
     * @param starTreeDocument   segment star-tree document
     * @return merged star-tree document
     */
    @SuppressWarnings("unchecked")
    public StarTreeDocument reduceStarTreeDocuments(StarTreeDocument aggregatedDocument, StarTreeDocument starTreeDocument) {
        // aggregate the documents
        if (aggregatedDocument == null) {
            Long[] dimensions = Arrays.copyOf(starTreeDocument.dimensions, numDimensions);
            Object[] metrics = new Object[numMetrics];
            for (int i = 0; i < numMetrics; i++) {
                try {
                    metrics[i] = metricAggregatorInfos.get(i).getValueAggregators().getInitialAggregatedValue(starTreeDocument.metrics[i]);
                } catch (Exception e) {
                    logger.error("Cannot get value for aggregation", e);
                    throw new IllegalStateException("Cannot get value for aggregation[" + starTreeDocument.metrics[i] + "]");
                }
            }
            return new StarTreeDocument(dimensions, metrics);
        } else {
            for (int i = 0; i < numMetrics; i++) {
                try {
                    aggregatedDocument.metrics[i] = metricAggregatorInfos.get(i)
                        .getValueAggregators()
                        .mergeAggregatedValues(starTreeDocument.metrics[i], aggregatedDocument.metrics[i]);
                } catch (Exception e) {
                    logger.error("Cannot apply value to aggregated document for aggregation", e);
                    throw new IllegalStateException(
                        "Cannot apply value to aggregated document for aggregation [" + starTreeDocument.metrics[i] + "]"
                    );
                }
            }
            return aggregatedDocument;
        }
    }

    /**
     * Converts numericDocValues to sortedNumericDocValues and returns SequentialDocValuesIterator
     */
    private SequentialDocValuesIterator getIteratorForNumericField(
        Map<String, DocValuesProducer> fieldProducerMap,
        FieldInfo fieldInfo,
        String name
    ) throws IOException {
        if (fieldInfo == null) {
            fieldInfo = getFieldInfo(name, DocValuesType.NUMERIC);
        }
        SequentialDocValuesIterator sequentialDocValuesIterator;
        assert fieldProducerMap.containsKey(fieldInfo.name);
        sequentialDocValuesIterator = new SequentialDocValuesIterator(
            DocValues.singleton(fieldProducerMap.get(fieldInfo.name).getNumeric(fieldInfo))
        );
        return sequentialDocValuesIterator;
    }

    /**
     * Adds a document to star-tree
     *
     * @param starTreeDocument star-tree document
     * @throws IOException throws an exception if we are unable to add the doc
     */
    private void appendToStarTree(StarTreeDocument starTreeDocument) throws IOException {
        appendStarTreeDocument(starTreeDocument);
        numStarTreeDocs++;
    }

    /**
     * Returns a new star-tree node
     *
     * @return return new star-tree node
     */
    private InMemoryTreeNode getNewNode() {
        numStarTreeNodes++;
        return new InMemoryTreeNode();
    }

    /**
     * Returns a new star-tree node
     * @param dimensionId dimension id of the star-tree node
     * @param startDocId start doc id of the star-tree node
     * @param endDocId end doc id of the star-tree node
     * @param nodeType node type of the star-tree node
     * @param dimensionValue dimension value of the star-tree node
     * @return
     */
    private InMemoryTreeNode getNewNode(int dimensionId, int startDocId, int endDocId, byte nodeType, long dimensionValue) {
        numStarTreeNodes++;
        return new InMemoryTreeNode(dimensionId, startDocId, endDocId, nodeType, dimensionValue);
    }

    /**
     * Implements the algorithm to construct a star-tree
     *
     * @param node       star-tree node
     * @param startDocId start document id
     * @param endDocId   end document id
     * @throws IOException throws an exception if we are unable to construct the tree
     */
    private void constructStarTree(InMemoryTreeNode node, int startDocId, int endDocId) throws IOException {

        int childDimensionId = node.getDimensionId() + 1;
        if (childDimensionId == numDimensions) {
            return;
        }

        // Construct all non-star children nodes
        node.setChildDimensionId(childDimensionId);
        constructNonStarNodes(node, startDocId, endDocId, childDimensionId);

        // Construct star-node if required
        if (!skipStarNodeCreationForDimensions.contains(childDimensionId) && node.getChildren().size() > 1) {
            node.addChildNode(constructStarNode(startDocId, endDocId, childDimensionId), (long) ALL);
        }

        // Further split star node if needed
        if (node.getChildStarNode() != null
            && (node.getChildStarNode().getEndDocId() - node.getChildStarNode().getStartDocId() > maxLeafDocuments)) {
            constructStarTree(node.getChildStarNode(), node.getChildStarNode().getStartDocId(), node.getChildStarNode().getEndDocId());
        }

        // Further split on child nodes if required
        for (InMemoryTreeNode child : node.getChildren().values()) {
            if (child.getEndDocId() - child.getStartDocId() > maxLeafDocuments) {
                constructStarTree(child, child.getStartDocId(), child.getEndDocId());
            }
        }

    }

    /**
     * Constructs non star tree nodes
     *
     * @param node parent node
     * @param startDocId  start document id (inclusive)
     * @param endDocId    end document id (exclusive)
     * @param dimensionId id of the dimension in the star tree
     *
     * @throws IOException throws an exception if we are unable to construct non-star nodes
     */
    private void constructNonStarNodes(InMemoryTreeNode node, int startDocId, int endDocId, int dimensionId) throws IOException {
        int nodeStartDocId = startDocId;
        Long nodeDimensionValue = getDimensionValue(startDocId, dimensionId);
        for (int i = startDocId + 1; i < endDocId; i++) {
            Long dimensionValue = getDimensionValue(i, dimensionId);
            if (Objects.equals(dimensionValue, nodeDimensionValue) == false) {
                addChildNode(node, i, dimensionId, nodeStartDocId, nodeDimensionValue);

                nodeStartDocId = i;
                nodeDimensionValue = dimensionValue;
            }
        }
        addChildNode(node, endDocId, dimensionId, nodeStartDocId, nodeDimensionValue);
    }

    private void addChildNode(InMemoryTreeNode node, int endDocId, int dimensionId, int nodeStartDocId, Long nodeDimensionValue) {
        long childNodeDimensionValue;
        byte childNodeType;
        if (nodeDimensionValue == null) {
            childNodeDimensionValue = ALL;
            childNodeType = StarTreeNodeType.NULL.getValue();
        } else {
            childNodeDimensionValue = nodeDimensionValue;
            childNodeType = StarTreeNodeType.DEFAULT.getValue();
        }

        InMemoryTreeNode lastNode = getNewNode(dimensionId, nodeStartDocId, endDocId, childNodeType, childNodeDimensionValue);
        node.addChildNode(lastNode, nodeDimensionValue);
    }

    /**
     * Constructs star tree nodes
     *
     * @param startDocId  start document id (inclusive)
     * @param endDocId    end document id (exclusive)
     * @param dimensionId id of the dimension in the star tree
     * @return root node with star nodes constructed
     * @throws IOException throws an exception if we are unable to construct non-star nodes
     */
    private InMemoryTreeNode constructStarNode(int startDocId, int endDocId, int dimensionId) throws IOException {
        int starNodeStartDocId = numStarTreeDocs;
        Iterator<StarTreeDocument> starTreeDocumentIterator = generateStarTreeDocumentsForStarNode(startDocId, endDocId, dimensionId);
        appendDocumentsToStarTree(starTreeDocumentIterator);
        return getNewNode(dimensionId, starNodeStartDocId, numStarTreeDocs, StarTreeNodeType.STAR.getValue(), ALL);
    }

    /**
     * Returns aggregated star-tree document
     *
     * @param node star-tree node
     * @return aggregated star-tree documents
     * @throws IOException throws an exception upon failing to create new aggregated docs based on star tree
     */
    private StarTreeDocument createAggregatedDocs(InMemoryTreeNode node) throws IOException {
        StarTreeDocument aggregatedStarTreeDocument = null;

        // For leaf node
        if (!node.hasChild()) {

            if (node.getStartDocId() == node.getEndDocId() - 1) {
                // If it has only one document, use it as the aggregated document
                aggregatedStarTreeDocument = getStarTreeDocument(node.getStartDocId());
                node.setAggregatedDocId(node.getStartDocId());
            } else {
                // If it has multiple documents, aggregate all of them
                for (int i = node.getStartDocId(); i < node.getEndDocId(); i++) {
                    aggregatedStarTreeDocument = reduceStarTreeDocuments(aggregatedStarTreeDocument, getStarTreeDocument(i));
                }
                if (null == aggregatedStarTreeDocument) {
                    throw new IllegalStateException("aggregated star-tree document is null after reducing the documents");
                }
                for (int i = node.getDimensionId() + 1; i < numDimensions; i++) {
                    aggregatedStarTreeDocument.dimensions[i] = STAR_IN_DOC_VALUES_INDEX;
                }
                node.setAggregatedDocId(numStarTreeDocs);
                appendToStarTree(aggregatedStarTreeDocument);
            }
        } else {
            // For non-leaf node
            if (node.getChildStarNode() != null) {
                // If it has star child, use the star child aggregated document directly
                aggregatedStarTreeDocument = createAggregatedDocs(node.getChildStarNode());
                node.setAggregatedDocId(node.getChildStarNode().getAggregatedDocId());

                for (InMemoryTreeNode child : node.getChildren().values()) {
                    createAggregatedDocs(child);
                }
            } else {
                // If no star child exists, aggregate all aggregated documents from non-star children
                if (node.getChildren().values().size() == 1) {
                    for (InMemoryTreeNode child : node.getChildren().values()) {
                        aggregatedStarTreeDocument = reduceStarTreeDocuments(aggregatedStarTreeDocument, createAggregatedDocs(child));
                        node.setAggregatedDocId(child.getAggregatedDocId());
                    }
                } else {
                    for (InMemoryTreeNode child : node.getChildren().values()) {
                        aggregatedStarTreeDocument = reduceStarTreeDocuments(aggregatedStarTreeDocument, createAggregatedDocs(child));
                    }
                    if (null == aggregatedStarTreeDocument) {
                        throw new IllegalStateException("aggregated star-tree document is null after reducing the documents");
                    }
                    for (int i = node.getDimensionId() + 1; i < numDimensions; i++) {
                        aggregatedStarTreeDocument.dimensions[i] = STAR_IN_DOC_VALUES_INDEX;
                    }
                    node.setAggregatedDocId(numStarTreeDocs);
                    appendToStarTree(aggregatedStarTreeDocument);
                }
            }
        }
        return aggregatedStarTreeDocument;
    }

    /**
     * Handles the dimension of date time field type
     *
     * @param fieldName name of the field
     * @param val       value of the field
     * @return returns the converted dimension of the field to a particular granularity
     */
    private long handleDateDimension(final String fieldName, final long val) {
        // TODO: handle timestamp granularity
        return val;
    }

    public void close() throws IOException {

    }

    abstract Iterator<StarTreeDocument> mergeStarTrees(List<StarTreeValues> starTreeValues) throws IOException;

    public InMemoryTreeNode getRootNode() {
        return rootNode;
    }
}
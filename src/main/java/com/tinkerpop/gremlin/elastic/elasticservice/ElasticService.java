package com.tinkerpop.gremlin.elastic.elasticservice;

import com.eaio.uuid.UUID;
import com.tinkerpop.gremlin.elastic.elasticservice.TimingAccessor.Timer;
import com.tinkerpop.gremlin.elastic.structure.*;
import com.tinkerpop.gremlin.structure.*;
import com.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.commons.configuration.Configuration;
import org.elasticsearch.action.bulk.*;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.get.*;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.*;
import org.elasticsearch.action.update.UpdateRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.*;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.index.query.*;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.*;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.util.*;
import java.util.stream.*;

public class ElasticService {

    //region members

    public static class ClientType {
        public static String TRANSPORT_CLIENT = "TRANSPORT_CLIENT";
        public static String NODE_CLIENT = "NODE_CLIENT";
        public static String NODE = "NODE";
    }

    private ElasticGraph graph;
    public SchemaProvider schemaProvider;
    private boolean refresh;
    BulkRequestBuilder bulkRequest;
    private final String clusterName;
    private final String addresses;
    public Client client;
    private Node node;
    TimingAccessor timingAccessor = new TimingAccessor();

    //endregion

    //region initialization

    public ElasticService(ElasticGraph graph, Configuration configuration) throws IOException {
        timer("initialization").start();

        this.graph = graph;
        this.refresh = configuration.getBoolean("elasticsearch.refresh", true);
        this.clusterName = configuration.getString("elasticsearch.cluster.name", "elasticsearch");
        this.addresses = configuration.getString("elasticsearch.cluster.address", "127.0.0.1");
        boolean bulk = configuration.getBoolean("elasticsearch.batch", false);
        String schemaProvider = configuration.getString("elasticsearch.schemaProvider", DefaultSchemaProvider.class.getCanonicalName());
        String clientType =configuration.getString("elasticsearch.client", ClientType.NODE);

        if (clientType.equals(ClientType.TRANSPORT_CLIENT)) createTransportClient();
        else if (clientType.equals(ClientType.NODE_CLIENT)) createNode(true);
        else if (clientType.equals(ClientType.NODE)) createNode(false);
        else throw new IllegalArgumentException("clientType unknown:" + clientType);

        try {
            Class c = Class.forName(schemaProvider);
            this.schemaProvider = (SchemaProvider) c.newInstance();
            this.schemaProvider.init(this.client, configuration);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to load SchemaProvider:" + schemaProvider, e);
        }

        if(bulk)
            bulkRequest = client.prepareBulk();

        timer("initialization").stop();
    }

    private void createTransportClient() {
        Settings settings = ImmutableSettings.settingsBuilder().put("cluster.name", clusterName).build();
        TransportClient transportClient = new TransportClient(settings);
        for(String address : addresses.split(","))
            transportClient.addTransportAddress(new InetSocketTransportAddress(address, 9300));
        this.client = transportClient;
    }

    private void createNode(boolean client) {
        this.node = NodeBuilder.nodeBuilder().client(client).clusterName(clusterName).build();
        node.start();
        this.client = node.client();
    }

    public void close() {
        client.close();
        schemaProvider.close();
        if (node != null) node.close();
        timingAccessor.print();
    }

    public void commit() {
        if(bulkRequest == null) return;
        timer("bulk execute").start();
        BulkResponse bulkItemResponses = bulkRequest.execute().actionGet();
        bulkRequest = client.prepareBulk();
        timer("bulk execute").stop();
    }

    //endregion

    //region queries

    public String addElement(String label, Object idValue, ElasticElement.Type type, Object... keyValues) {
        timer("add element").start();

        for (int i = 0; i < keyValues.length; i = i + 2) {
            String key = keyValues[i].toString();
            Object value = keyValues[i + 1];
            ElementHelper.validateProperty(key, value);
        }
        if(idValue == null) idValue = new UUID().toString();

        SchemaProvider.AddElementResult addElementResult = schemaProvider.addElement(label, idValue, type, keyValues);

        IndexRequestBuilder indexRequestBuilder =
                client.prepareIndex(addElementResult.getIndex(), label, addElementResult.getId()).
                setCreate(true).setSource(addElementResult.getKeyValues());

        if(bulkRequest != null) bulkRequest.add(indexRequestBuilder);
        else indexRequestBuilder.execute().actionGet();

        timer("add element").stop();
        return addElementResult.getId();
    }

    public void deleteElement(Element element) {
        timer("remove element").start();
        DeleteRequestBuilder deleteRequestBuilder = client.prepareDelete(schemaProvider.getIndex(element), element.label(), element.id().toString());
        if(bulkRequest != null) bulkRequest.add(deleteRequestBuilder);
        else deleteRequestBuilder.execute().actionGet();
        timer("remove element").stop();
    }

    public void deleteElements(Iterator<Element> elements) {
        elements.forEachRemaining(this::deleteElement);
    }

    public <V> void addProperty(Element element, String key, V value) {
        timer("update property").start();
        UpdateRequestBuilder updateRequestBuilder = client.prepareUpdate(schemaProvider.getIndex(element), element.label(), element.id().toString()).setDoc(key, value);
        if(bulkRequest != null) bulkRequest.add(updateRequestBuilder);
        else updateRequestBuilder.execute().actionGet();
        timer("update property").stop();
    }

    public void removeProperty(Element element, String key) {
        timer("remove property").start();
        UpdateRequestBuilder updateRequestBuilder = client.prepareUpdate(schemaProvider.getIndex(element), element.label(), element.id().toString()).setScript("ctx._source.remove(\"" + key + "\")", ScriptService.ScriptType.INLINE);
        if(bulkRequest != null) bulkRequest.add(updateRequestBuilder);
        else updateRequestBuilder.execute().actionGet();
        timer("remove property").stop();
    }

    public Iterator<Vertex> getVertices(String type,Object... ids) {
        if (ids == null || ids.length == 0) return Collections.emptyIterator();

        MultiGetResponse responses = get(type,ids);
        ArrayList<Vertex> vertices = new ArrayList<>(ids.length);
        for (MultiGetItemResponse getResponse : responses) {
            GetResponse response = getResponse.getResponse();
            if(!response.isExists()) continue;
            vertices.add(createVertex(response.getId(), response.getType(), response.getSource()));
        }
        return vertices.iterator();
    }

    public Iterator<Edge> getEdges(String type, Object... ids) {
        if (ids == null || ids.length == 0) return Collections.emptyIterator();

        MultiGetResponse responses = get(type,ids);
        ArrayList<Edge> edges = new ArrayList<>(ids.length);
        for (MultiGetItemResponse getResponse : responses) {
            GetResponse response = getResponse.getResponse();
            if (!response.isExists()) throw Graph.Exceptions.elementNotFound(Edge.class, response.getId());
            edges.add(createEdge(response.getId(), response.getType(), response.getSource()));
        }
        return edges.iterator();
    }

    public Iterator<Vertex> searchVertices(BoolFilterBuilder filter, Object[] ids, String[] labels) {
        if(idsOnlyQuery(filter, ids, labels)) return getVertices(getFirstOrDefaultLabel(labels),ids);
        FilterBuilder finalFilter = (ids != null && ids.length > 0) ? idsFilter(filter, ids) : filter;
        Stream<SearchHit> hits = search(finalFilter, ElasticElement.Type.vertex, labels);
        return hits.map((hit) -> createVertex(hit.getId(), hit.getType(), hit.getSource())).iterator();
    }

    private String getFirstOrDefaultLabel(String[] labels) {
        if(labels == null || labels.length == 0) return null;
        return labels[0];
    }

    private boolean idsOnlyQuery(BoolFilterBuilder filter, Object[] ids, String[] labels) {
        return (filter == null || !filter.hasClauses()) &&(labels == null || labels.length <= 1) && ids != null && ids.length > 0;
    }

    public Iterator<Edge> searchEdges(BoolFilterBuilder filter, Object[] ids, String[] labels) {
        if(idsOnlyQuery(filter,ids,labels)) getEdges(getFirstOrDefaultLabel(labels),ids);
        FilterBuilder finalFilter = (ids != null && ids.length > 0) ? idsFilter(filter, ids) : filter;
        Stream<SearchHit> hits = search(finalFilter, ElasticElement.Type.edge, labels);
        return hits.map((hit) -> createEdge(hit.getId(), hit.getType(), hit.getSource())).iterator();
    }


    public static FilterBuilder idsFilter(BoolFilterBuilder boolFilterBuilder, Object[] ids) {
        String[] stringIds = new String[ids.length];
        for(int i = 0; i<ids.length; i++)
            stringIds[i] = ids[i].toString();
        IdsFilterBuilder idsFilterBuilder = FilterBuilders.idsFilter().addIds(stringIds);
        if(boolFilterBuilder.hasClauses())
            return FilterBuilders.andFilter(boolFilterBuilder, idsFilterBuilder);
        return idsFilterBuilder;
    }

    private MultiGetResponse get(String type,Object[] ids) {
        timer("get").start();
        MultiGetRequest request = new MultiGetRequest();
        for (Object id : ids)
            request.add(schemaProvider.getIndex(id), type, id.toString());
        MultiGetResponse multiGetItemResponses = client.multiGet(request).actionGet();
        timer("get").stop();
        return multiGetItemResponses;
    }

    private Stream<SearchHit> search(FilterBuilder filter, ElasticElement.Type type, String... labels) {
        timer("search").start();

        SchemaProvider.SearchResult result = schemaProvider.search(filter, type, labels);

        if (refresh) client.admin().indices().prepareRefresh(result.getIndices()).execute().actionGet();

        SearchRequestBuilder searchRequestBuilder = client.prepareSearch(result.getIndices())
                .setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(),result.getFilter())).setFrom(0).setSize(2000000); //TODO: retrive with scroll for efficiency
        if (labels != null && labels.length > 0 && labels[0] != null)
            searchRequestBuilder = searchRequestBuilder.setTypes(labels);

        SearchResponse searchResponse = searchRequestBuilder.execute().actionGet();

        Iterable<SearchHit> hitsIterable = () -> searchResponse.getHits().iterator();
        Stream<SearchHit> hitStream = StreamSupport.stream(hitsIterable.spliterator(), false);
        timer("search").stop();
        return hitStream;
    }


    private Vertex createVertex(Object id, String label, Map<String, Object> fields) {
        ElasticVertex vertex = new ElasticVertex(id, label, null, graph);
        fields.entrySet().forEach((field) -> vertex.addPropertyLocal(field.getKey(), field.getValue()));
        return vertex;
    }

    private Edge createEdge(Object id, String label, Map<String, Object> fields) {
        ElasticEdge edge = new ElasticEdge(id, label, fields.get(ElasticEdge.OutId), fields.get(ElasticEdge.InId), null, graph);
        fields.entrySet().forEach((field) -> edge.addPropertyLocal(field.getKey(), field.getValue()));
        return edge;
    }

    //endregion

    //region meta

    private Timer timer(String name) {
        return timingAccessor.timer(name);
    }

    public void collectData() {
        timingAccessor.print();
    }

    @Override
    public String toString() {
        return "ElasticService{" +
                "schema='" + schemaProvider + '\'' +
                ", client=" + client +
                '}';
    }
    //endregion
}

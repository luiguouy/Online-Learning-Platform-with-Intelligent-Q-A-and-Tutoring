package com.smartqa.platform.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自建薄 Chroma 向量存储 —— <b>Issue #65 根治</b>（替换 {@code langchain4j-chroma 0.35} 的
 * {@code ChromaEmbeddingStore}，接口面完全兼容业务代码）。
 *
 * <h3>为什么存在（#65 根因链，全部为 chromadb 0.6.3 运行时实测，非推断）</h3>
 * <p>{@code ChromaEmbeddingStore} 构造期无条件 {@code POST /api/v1/collections}（创建），
 * collection 已存在时服务端返回 <b>409 UniqueConstraintError</b> → Spring Bean 创建失败 →
 * 后端二次启动即挂。升级 langchain4j 0.36.2 已被实测否证：其存在性检查走
 * {@code GET /api/v1/collections/{name}}（按名字），而 0.6.3 该端点因 coroutine bug 恒回
 * 400，"已存在"被误判为"不存在"→ 照样 409。</p>
 * <p>0.6.3 v1 REST 的实测语义面（本类设计的直接依据）：</p>
 * <ul>
 *   <li>{@code POST /collections}（按 name 创建）：新名 200 返回 id；重复名 409；忽略客户端自带 id</li>
 *   <li>{@code PUT /collections/{uuid}}：按 <b>UUID</b> 判存在（存在 200 / 不存在 400 InvalidCollection），
 *       纯读无副作用（ghost uuid + 任意 name 也不会创建）——<b>唯一可用的存在性探测</b></li>
 *   <li>{@code POST /collections/{uuid}/add|query|delete}：路径参数必须是 UUID（按 name 恒 400 InvalidUUID）</li>
 *   <li>{@code GET /collections}、{@code GET/DELETE /collections/{name}}：coroutine bug 全废（400）；
 *       {@code .../get_or_create} 路由不存在（404）；v2 仅 heartbeat</li>
 * </ul>
 * <p>⇒ "name → uuid" 反查在 0.6.3 上不可能，本类的方案是 <b>自己持久化 uuid</b>：
 * 创建成功后把 collection id 写入本地缓存文件，重启时用 {@code PUT} 探测复用；
 * 卷被清（探测失败）则重建并更新缓存。存量环境交接（有 collection 无缓存）通过
 * 配置项 {@code rag.chroma.collection-id} 一次性注入。</p>
 *
 * <h3>与既有语义的兼容承诺（#82 / #58 验收项）</h3>
 * <ul>
 *   <li>score 公式与 langchain4j-chroma 0.35 逐字节一致：{@code score = 1 - distance / 2}
 *       （{@code distanceToScore} 反编译核实，L2 空间），相关度得分展示口径不变；</li>
 *   <li>metadata 原样透传（值均为字符串形态，配合 #82 读取侧兼容）；</li>
 *   <li>{@code removeAll(IsEqualTo)} 等价原 {@code ChromaMetadataFilterMapper} 的 where 简写
 *       {@code {key: value}}，承载 #58 的按 docId 隔离删除。</li>
 * </ul>
 *
 * <p>使用面刻意收窄：本实现只支持业务代码实际用到的 {@code addAll} / {@code search} /
 * {@code removeAll(Filter)}（IsEqualTo）与按 id 删除；其余 Filter 组合抛
 * {@link UnsupportedOperationException} 而非静默给错语义——将来需要时按
 * {@code ChromaMetadataFilterMapper} 对照扩展。</p>
 */
public class ChromaThinEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(ChromaThinEmbeddingStore.class);

    private static final String V1 = "/api/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String collectionName;
    private final Path idCacheFile;
    /** 解析结果：Chroma 服务端生成的 collection UUID，所有数据面请求都以它为路径参数。 */
    private final String collectionId;

    private ChromaThinEmbeddingStore(Builder b) {
        this.baseUrl = stripTrailingSlash(b.baseUrl);
        this.collectionName = b.collectionName;
        this.idCacheFile = b.idCacheDir.resolve("collection-" + b.collectionName + ".id");
        this.httpClient = HttpClient.newBuilder()
                // 【#65 实测】Chroma 的 uvicorn/h11 会直接拒绝 Java HttpClient 默认 HTTP/2 对明文端口
                // 的 h2c 升级/先验知识（回 400 "Invalid HTTP request received"），必须钉死 1.1（与 curl 同构）。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.collectionId = resolveCollectionId(b.collectionId);
        log.info("[#65] Chroma collection 就绪: name={}, id={}", collectionName, collectionId);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 解析后的 collection UUID（包级可见：供同包验收测试断言复用/重建路径，不对外暴露）。 */
    String collectionId() {
        return collectionId;
    }

    /** 装配参数：地址、集合名、可选的迁移注入 id、uuid 缓存目录。 */
    public static class Builder {
        private String baseUrl;
        private String collectionName;
        private String collectionId;
        private Path idCacheDir = Path.of(".chroma-cache");

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder collectionName(String name) { this.collectionName = name; return this; }
        public Builder collectionId(String id) { this.collectionId = (id == null || id.isBlank()) ? null : id.trim(); return this; }
        public Builder idCacheDir(Path dir) { this.idCacheDir = dir; return this; }

        public ChromaThinEmbeddingStore build() {
            if (baseUrl == null || collectionName == null) {
                throw new IllegalArgumentException("ChromaThinEmbeddingStore 需要 baseUrl 与 collectionName");
            }
            return new ChromaThinEmbeddingStore(this);
        }
    }

    // ════════════════════════════ collection 解析（#65 修复核心） ════════════════════════════

    /**
     * 解析状态机（顺序即优先级）：
     * <ol>
     *   <li>显式配置 id（迁移通道）→ PUT 探测存在即用，并写入缓存；</li>
     *   <li>缓存文件 id → PUT 探测存在即用（<b>常规重启路径</b>）；</li>
     *   <li>POST 创建（新名）→ 200 拿 id 写缓存（首次启动 / 卷被清后的自愈路径）；</li>
     *   <li>创建遇 409（collection 存在但 uuid 无从得知——0.6.3 反查面全废）→
     *       抛带两条可行动出口的启动错误，绝不伪装成成功。</li>
     * </ol>
     */
    private String resolveCollectionId(String configuredId) {
        List<String> candidates = new ArrayList<>();
        if (configuredId != null) {
            candidates.add(configuredId);
        }
        String cached = readCachedId(idCacheFile);
        if (cached != null && !candidates.contains(cached)) {
            candidates.add(cached);
        }
        for (String candidate : candidates) {
            if (collectionExists(candidate)) {
                log.info("[#65] 复用已存在 collection（PUT 探测 200）: id={}", candidate);
                writeCachedId(idCacheFile, candidate); // 配置注入的 id 也落缓存，后续重启不再依赖配置
                return candidate;
            }
        }
        try {
            String created = createCollection();
            writeCachedId(idCacheFile, created);
            log.info("[#65] 新建 collection: name={}, id={}（uuid 已缓存至 {}）", collectionName, created, idCacheFile);
            return created;
        } catch (CollectionAlreadyExistsException e) {
            throw new IllegalStateException("""
                    [#65] Chroma collection「%s」已存在，但其 UUID 无法获知（0.6.3 的按名查询端点全部损坏，\
                    而本地缓存 %s 缺失或已过期）。二选一恢复：\
                    ① 保留数据：查出该 collection 的 UUID 后配置 rag.chroma.collection-id（或环境变量 \
                    RAG_CHROMA_COLLECTION_ID）再重启；② 接受重灌：清空 Chroma 数据卷/删除该 collection 后重启，\
                    本组件会自动重建并缓存 UUID。""".formatted(collectionName, idCacheFile), e);
        }
    }

    /** PUT /collections/{uuid}：200 即存在；其余（400 InvalidCollection 等）一律视为不存在。 */
    private boolean collectionExists(String uuid) {
        try {
            HttpResponse<String> r = sendRaw("PUT", V1 + "/collections/" + uuid,
                    MAPPER.writeValueAsString(Map.of("name", collectionName)));
            return r.statusCode() == 200;
        } catch (Exception e) {
            log.warn("[#65] collection 存在性探测异常（按不存在处理，继续走创建路径）: {}", e.toString());
            return false;
        }
    }

    private String createCollection() {
        try {
            HttpResponse<String> r = sendRaw("POST", V1 + "/collections",
                    MAPPER.writeValueAsString(Map.of("name", collectionName)));
            if (r.statusCode() == 200 || r.statusCode() == 201) {
                return MAPPER.readTree(r.body()).path("id").asText();
            }
            if (r.statusCode() == 409 || (r.body() != null && r.body().contains("UniqueConstraintError"))) {
                throw new CollectionAlreadyExistsException();
            }
            throw new IllegalStateException("[#65] 创建 Chroma collection 失败: HTTP "
                    + r.statusCode() + " " + abbreviate(r.body()));
        } catch (CollectionAlreadyExistsException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("[#65] 创建 Chroma collection 失败: " + e.getMessage(), e);
        }
    }

    /** 内部信号：collection 名已被占用且 uuid 不可知（0.6.3 服务端把所有"按名反查"端点都坏了）。 */
    private static class CollectionAlreadyExistsException extends RuntimeException {
        CollectionAlreadyExistsException() { super("collection already exists"); }
    }

    // ── uuid 缓存文件（纯函数，包级可见供单测） ──

    static String readCachedId(Path cacheFile) {
        try {
            if (!Files.isRegularFile(cacheFile)) {
                return null;
            }
            String text = Files.readString(cacheFile).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            log.warn("[#65] 读取 collection id 缓存失败（忽略，按缺失处理）: {}", e.toString());
            return null;
        }
    }

    static void writeCachedId(Path cacheFile, String id) {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, id);
        } catch (IOException e) {
            // 缓存写失败不致命：下次重启退化为"创建遇 409 → 明确报错"，不能让静默坏状态继续
            log.warn("[#65] 写入 collection id 缓存失败（下次重启可能需手工注入 collection-id）: {}", e.toString());
        }
    }

    // ════════════════════════════ EmbeddingStore 接口实现 ════════════════════════════

    @Override
    public String add(Embedding embedding) {
        String id = UUID.randomUUID().toString();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        addAllInternal(List.of(id), List.of(embedding), null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = UUID.randomUUID().toString();
        addAllInternal(List.of(id), List.of(embedding), List.of(textSegment));
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return addAllInternal(null, embeddings, null);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        if (embedded != null && embeddings.size() != embedded.size()) {
            throw new IllegalArgumentException(
                    "embeddings 与 embedded 长度不一致：" + embeddings.size() + " vs " + embedded.size());
        }
        return addAllInternal(null, embeddings, embedded);
    }

    private List<String> addAllInternal(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> effectiveIds = ids != null ? ids
                : embeddings.stream().map(e -> UUID.randomUUID().toString()).toList();
        try {
            Map<String, Object> body = buildAddBody(effectiveIds, embeddings, segments);
            HttpResponse<String> r = sendRaw("POST",
                    V1 + "/collections/" + collectionId + "/add", MAPPER.writeValueAsString(body));
            require2xx(r, "写入向量");
            return effectiveIds;
        } catch (IOException e) {
            throw new IllegalStateException("[#65] 写入 Chroma 向量失败: " + e.getMessage(), e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        try {
            Map<String, Object> body = buildQueryBody(
                    request.queryEmbedding(), request.maxResults(),
                    request.filter() == null ? null : whereFor(request.filter()));
            HttpResponse<String> r = sendRaw("POST",
                    V1 + "/collections/" + collectionId + "/query", MAPPER.writeValueAsString(body));
            require2xx(r, "向量检索");
            List<EmbeddingMatch<TextSegment>> matches = parseQueryMatches(MAPPER.readTree(r.body()));
            double minScore = request.minScore();
            List<EmbeddingMatch<TextSegment>> kept = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> m : matches) {
                if (m.score() != null && m.score() >= minScore) {
                    kept.add(m);
                }
            }
            return new EmbeddingSearchResult<>(kept);
        } catch (IOException e) {
            throw new IllegalStateException("[#65] Chroma 向量检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeAll(Filter filter) {
        if (!(filter instanceof IsEqualTo eq)) {
            throw new UnsupportedOperationException(
                    "[#65] 薄 Chroma store 目前仅支持 IsEqualTo 过滤（业务唯一用法，见 #58 按 docId 删除）；"
                            + "收到 " + filter.getClass().getSimpleName() + "，需要时按 ChromaMetadataFilterMapper 扩展");
        }
        try {
            Map<String, Object> body = Map.of("where", Map.of(eq.key(), String.valueOf(eq.comparisonValue())));
            HttpResponse<String> r = sendRaw("POST",
                    V1 + "/collections/" + collectionId + "/delete", MAPPER.writeValueAsString(body));
            require2xx(r, "按元数据删除向量");
        } catch (IOException e) {
            throw new IllegalStateException("[#65] Chroma 删除向量失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void remove(String embeddingId) {
        removeAll(List.of(embeddingId));
    }

    @Override
    public void removeAll(java.util.Collection<String> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of("ids", new ArrayList<>(embeddingIds));
            HttpResponse<String> r = sendRaw("POST",
                    V1 + "/collections/" + collectionId + "/delete", MAPPER.writeValueAsString(body));
            require2xx(r, "按 id 删除向量");
        } catch (IOException e) {
            throw new IllegalStateException("[#65] Chroma 删除向量失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void removeAll() {
        throw new UnsupportedOperationException(
                "[#65] 薄 Chroma store 不支持全量清空（业务无此路径；请走 Chroma 运维清卷或按 docId 删除）");
    }

    // ── 请求体构造与响应解析（纯函数，包级可见供单测钉住，手法同 #82 解析规则单测） ──

    static Map<String, Object> buildAddBody(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (Embedding e : embeddings) {
            vectors.add(e.vector());
        }
        var builder = new java.util.HashMap<String, Object>();
        builder.put("ids", ids);
        builder.put("embeddings", vectors);
        if (segments != null && segments.size() == embeddings.size()) {
            List<Map<String, Object>> metadatas = new ArrayList<>(segments.size());
            List<String> documents = new ArrayList<>(segments.size());
            for (TextSegment s : segments) {
                metadatas.add(s != null && s.metadata() != null ? s.metadata().toMap() : Map.of());
                documents.add(s != null ? s.text() : "");
            }
            builder.put("metadatas", metadatas);
            builder.put("documents", documents);
        }
        return builder;
    }

    static Map<String, Object> buildQueryBody(Embedding query, int nResults, Map<String, Object> where) {
        List<Float> vector = new ArrayList<>();
        for (float f : query.vector()) {
            vector.add(f);
        }
        var builder = new java.util.HashMap<String, Object>();
        builder.put("query_embeddings", List.of(vector));
        builder.put("n_results", nResults);
        builder.put("include", List.of("distances", "metadatas", "documents"));
        if (where != null) {
            builder.put("where", where);
        }
        return builder;
    }

    /** IsEqualTo → Chroma where 简写 {key: value}（与 langchain4j ChromaMetadataFilterMapper.mapEqual 同形）。 */
    static Map<String, Object> whereFor(Filter filter) {
        if (!(filter instanceof IsEqualTo eq)) {
            throw new UnsupportedOperationException("[#65] 仅支持 IsEqualTo: " + filter.getClass().getSimpleName());
        }
        return Map.of(eq.key(), String.valueOf(eq.comparisonValue()));
    }

    /** 与 langchain4j-chroma 0.35 的 distanceToScore 逐字节一致（反编译核实）：score = 1 - distance/2。 */
    static double distanceToScore(double distance) {
        return 1.0 - distance / 2.0;
    }

    static List<EmbeddingMatch<TextSegment>> parseQueryMatches(JsonNode root) {
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        JsonNode idsRow = root.path("ids").path(0);
        if (idsRow.isMissingNode() || !idsRow.isArray()) {
            return matches;
        }
        JsonNode distRow = root.path("distances").path(0);
        JsonNode metaRow = root.path("metadatas").path(0);
        JsonNode docRow = root.path("documents").path(0);
        for (int i = 0; i < idsRow.size(); i++) {
            String id = idsRow.get(i).asText();
            double distance = distRow.has(i) ? distRow.get(i).asDouble() : Double.NaN;
            String document = docRow.has(i) && !docRow.get(i).isNull() ? docRow.get(i).asText() : null;
            Map<String, Object> metadata = metaRow.has(i) && metaRow.get(i).isObject()
                    ? MAPPER.convertValue(metaRow.get(i), new TypeReference<Map<String, Object>>() {})
                    : Map.of();
            TextSegment segment = document == null ? null
                    : TextSegment.from(document, new Metadata(metadata));
            matches.add(new EmbeddingMatch<>(distanceToScore(distance), id, null, segment));
        }
        return matches;
    }

    // ════════════════════════════ HTTP 底座 ════════════════════════════

    /**
     * 底层发送：<b>原样返回任意状态码的响应</b>（调用方自行判断 409/400 等业务语义），
     * 仅网络层/中断异常被包装为 IllegalStateException 抛出。
     */
    private HttpResponse<String> sendRaw(String method, String path, String jsonBody) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            rb.method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
            return httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("[#65] Chroma 请求失败 " + method + " " + path + ": " + e.getMessage(), e);
        }
    }

    private void require2xx(HttpResponse<String> r, String action) {
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            // 把服务端 error 类别原样带进异常，便于日志定位（对照 #74/#83「故障归属要可辨」）
            throw new IllegalStateException("[#65] " + action + "失败: HTTP " + r.statusCode() + " " + abbreviate(r.body()));
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.replaceAll("/+$", "");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}

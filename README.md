
# 使Solr支持向量搜索的插件

基本上是基于这两个项目进行修改：

> https://github.com/moshebla/solr-vector-scoring
> https://github.com/DmitryKey/solr-vector-scoring

1. 这两个项目都基于[saaay71](https://github.com/saaay71/solr-vector-scoring)的实现进行修改，但原始项目的```VectorScoreQuery```基于```CustomScoreQuery```实现，只能支持Lucene 7.5以前的版本；
2. [DmitryKey](https://github.com/DmitryKey/solr-vector-scoring)的实现改用了```FunctionScoreQuery```，因此可以支持Lucene 8.x；
3. 原版的```VectorQParserPlugin```仅计算向量相似度，效率较低，[moshebla](https://github.com/moshebla/solr-vector-scoring)的实现在saaay71的基础上增加了LSH作为倒排索引，在```VectorQParserPlugin```中嵌套了基于LSH的rerank的子查询，即是先匹配LSH索引，得到Top N文档后再计算相似度。

本项目基本上是以上几个项目的缝合，可以在Solr 8.1.1版本实现LSH召回+向量相似度计算，项目可以跑通，但是性能方面没有进行测试。

## 插件安装

在```solrconfig.xml```文件中配置**Query解析器**及**数据更新处理器**：

```xml
    <!-- Query解析器 -->
    <queryParser name="vp" class="com.github.saaay71.solr.query.VectorQParserPlugin" />
    <!-- 数据更新处理器 -->
    <updateRequestProcessorChain name="DSSM">
        <processor class="com.github.saaay71.solr.updateprocessor.LSHUpdateProcessorFactory" >
            <int name="seed">5</int>
            <int name="buckets">50</int>
            <int name="stages">50</int>
            <int name="dimensions">256</int>
            <str name="field">dssm_vector</str>
			<str name="lsh_hash">_dssm_lsh_hash_</str>
			<str name="lsh_binary">_dssm_binary_vector_</str>
        </processor>
        <processor class="solr.RunUpdateProcessorFactory" />
    </updateRequestProcessorChain>
```

其中```queryParser```几乎不需要更改，```updateRequestProcessorChain```需要根据不同模型作相应的配置，如Deepwalk、DSSM等模型训练出不同的向量，每种向量都需要修改相关的Hash参数、字段名。

在```managed-schema```文件中配置字段：

```xml
  <!-- 存储原始向量的二进制数据 -->
  <fieldType name="VectorField" class="solr.BinaryField" stored="true" indexed="false" multiValued="false"/>
  <field name="_dssm_binary_vector_" type="VectorField" />
  <!-- 使用LSH进行倒排索引 -->
  <field name="_dssm_lsh_hash_" type="string" indexed="true" stored="true" multiValued="true"/>
  <!-- 存储原始向量的字符串数据，用于倒排索引后的向量相似度计算 -->
  <field name="dssm_vector" type="string" indexed="true" stored="true"/>
```

重启Solr即完成插件安装。

## 插件使用

使用已经训练好的Embedding进行测试：

```python
def update_solr_for_test(embedding_path, pid):
    import json
    import requests
    from requests.auth import HTTPBasicAuth

    embeddings = pickle.load(open(embedding_path, 'rb'))
    embedding = embeddings[pid]
    vector_str = ','.join(['%.4f' % dim for dim in embedding])

    bg_solr_select_url = 'http://{ip}:{port}/solr/{core}/select'
    bg_solr_update_url = 'http://{ip}:{port}/solr/{core}/update'
    headers = {'content-type': 'application/json'}

    select_params = {'q': 'products_id:%s' % pid}
    select_result = requests.get(
        bg_solr_select_url, params=select_params,
        headers=headers,
        auth=HTTPBasicAuth('{user}', '{pwd}')
    )
    solr_doc = json.loads(select_result.text)['response']['docs'][0]

    solr_doc['dssm_vector'] = vector_str
    update_data = [solr_doc]
    update_params = {'update.chain': 'DSSM', 'overwrite': 'true', 'commit': 'true'}
    update_result = requests.post(
        bg_solr_update_url, json=update_data, params=update_params,
        headers=headers,
        auth=HTTPBasicAuth('{user}', '{pwd}')
    )
    print(update_result.text)
```

可以看到在更新```dssm_vector```字段的同时，自动生成了```_dssm_lsh_hash_```和```_dssm_binary_vector_```：

```json
"products_id":"1450576",
"dssm_vector":"-1.2974,-0.5188,-0.4911,0.7187,……,-0.2363",
"_dssm_binary_vector_":"/7+mETS/BNAUvvtxdj83/LlAIz6rvz8hLT9tuLu/……",
"_dssm_lsh_hash_":["0_29","1_27","2_6","3_16",……,"49_13"],
```

执行查询：

```json
q={!vp f=dssm_vector lsh_hash="_dssm_lsh_hash_" chain="DSSM" vector="-1.4609,-0.4210,-0.4715,0.0417,……,-0.1001" lsh="true" reRankDocs="5"}
```

或：

```json
bq={!vp f=dssm_vector lsh_hash="_dssm_lsh_hash_" chain="DSSM" vector="-1.4609,-0.4210,-0.4715,0.0417,……,-0.1001" lsh="true" reRankDocs="5"}
```

注意```f```、```lsh_hash```、```chain```参数需要与各种字段的配置一致，```reRankDocs```主要用于控制LSH倒排匹配的数量。

通过```debugQuery```可以看到Query解析器的逻辑：

```json
{
  !rerank
    mainQuery='(ConstantScore(_dssm_lsh_hash_:0_3))^0.02 (ConstantScore(_dssm_lsh_hash_:1_27))^0.02 …… (ConstantScore(_dssm_lsh_hash_:49_19))^0.02'
	reRankQuery='FunctionScoreQuery({! type=vp f=dssm_vector vector=-1.4609,-0.4210,-0.4715,0.0417,……,-0.1001 lsh=false v=}, scored by cosine(dssm_vector, doc))'
	reRankDocs=5
	reRankWeight=1.0
}
```

## 参考

> [LSH的Java实现](https://github.com/tdebatty/java-LSH)
> [Super-Bit Locality-Sensitive Hashing](https://proceedings.neurips.cc/paper/2012/file/072b030ba126b2f4b2374f342be9ed44-Paper.pdf)。


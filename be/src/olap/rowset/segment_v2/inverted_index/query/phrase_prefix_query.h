// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include "olap/rowset//segment_v2/inverted_index/query/prefix_query.h"
#include "olap/rowset/segment_v2/inverted_index/query/phrase_query.h"

CL_NS_USE(search)

namespace doris::segment_v2 {

class PhrasePrefixQuery : public Query {
public:
    PhrasePrefixQuery(const std::shared_ptr<lucene::search::IndexSearcher>& searcher,
                      const TQueryOptions& query_options, const io::IOContext* io_ctx);
    ~PhrasePrefixQuery() override = default;

    void add(const InvertedIndexQueryInfo& query_info) override;
    void search(roaring::Roaring& roaring) override;

private:
    std::shared_ptr<lucene::search::IndexSearcher> _searcher;

    size_t _term_size = 0;
    int32_t _max_expansions = 50;
    PhraseQuery _phrase_query;
    PrefixQuery _prefix_query;
};

} // namespace doris::segment_v2
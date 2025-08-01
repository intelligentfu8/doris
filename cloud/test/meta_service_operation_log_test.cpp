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

#include <brpc/controller.h>
#include <fmt/format.h>
#include <gen_cpp/cloud.pb.h>
#include <gen_cpp/olap_file.pb.h>
#include <gtest/gtest.h>

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "common/defer.h"
#include "common/util.h"
#include "cpp/sync_point.h"
#include "meta-service/meta_service.h"
#include "meta-store/keys.h"
#include "meta-store/meta_reader.h"
#include "meta-store/txn_kv.h"
#include "meta-store/txn_kv_error.h"
#include "meta-store/versioned_value.h"

namespace doris::cloud {
extern std::unique_ptr<MetaServiceProxy> get_meta_service();
extern std::unique_ptr<MetaServiceProxy> get_meta_service(bool mock_resource_mgr);

// Convert a string to a hex-escaped string.
// A non-displayed character is represented as \xHH where HH is the hexadecimal value of the character.
// A displayed character is represented as itself.
static std::string escape_hex(std::string_view data) {
    std::string result;
    for (char c : data) {
        if (isprint(c)) {
            result += c;
        } else {
            result += fmt::format("\\x{:02x}", static_cast<unsigned char>(c));
        }
    }
    return result;
}

static size_t count_range(TxnKv* txn_kv, std::string_view begin = "",
                          std::string_view end = "\xFF") {
    std::unique_ptr<Transaction> txn;
    EXPECT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
    if (!txn) {
        return 0; // Failed to create transaction
    }

    FullRangeGetOptions opts;
    opts.txn = txn.get();
    auto iter = txn_kv->full_range_get(std::string(begin), std::string(end), std::move(opts));
    size_t total = 0;
    for (auto&& kvp = iter->next(); kvp.has_value(); kvp = iter->next()) {
        total += 1;
    }

    EXPECT_TRUE(iter->is_valid()); // The iterator should still be valid after the next call.
    return total;
}

static std::string dump_range(TxnKv* txn_kv, std::string_view begin = "",
                              std::string_view end = "\xFF") {
    std::unique_ptr<Transaction> txn;
    if (txn_kv->create_txn(&txn) != TxnErrorCode::TXN_OK) {
        return "Failed to create dump range transaction";
    }
    FullRangeGetOptions opts;
    opts.txn = txn.get();
    auto iter = txn_kv->full_range_get(std::string(begin), std::string(end), std::move(opts));
    std::string buffer;
    for (auto&& kv = iter->next(); kv.has_value(); kv = iter->next()) {
        buffer +=
                fmt::format("Key: {}, Value: {}\n", escape_hex(kv->first), escape_hex(kv->second));
    }
    EXPECT_TRUE(iter->is_valid()); // The iterator should still be valid after the next call.
    return buffer;
}

TEST(MetaServiceOperationLogTest, CommitPartitionLog) {
    auto meta_service = get_meta_service(false);
    std::string instance_id = "commit_partition_log";
    auto* sp = SyncPoint::get_instance();
    DORIS_CLOUD_DEFER {
        SyncPoint::get_instance()->clear_all_call_backs();
    };
    sp->set_call_back("get_instance_id", [&](auto&& args) {
        auto* ret = try_any_cast_ret<std::string>(args);
        ret->first = instance_id;
        ret->second = true;
    });
    sp->enable_processing();

    constexpr int64_t db_id = 123;
    constexpr int64_t table_id = 10001;
    constexpr int64_t index_id = 10002;
    constexpr int64_t partition_id = 10003;

    {
        // write instance
        InstanceInfoPB instance_info;
        instance_info.set_instance_id(instance_id);
        instance_info.set_multi_version_status(MULTI_VERSION_WRITE_ONLY);
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(meta_service->txn_kv()->create_txn(&txn), TxnErrorCode::TXN_OK);
        txn->put(instance_key(instance_id), instance_info.SerializeAsString());
        ASSERT_EQ(txn->commit(), TxnErrorCode::TXN_OK);

        meta_service->resource_mgr()->refresh_instance(instance_id);
        ASSERT_TRUE(meta_service->resource_mgr()->is_version_write_enabled(instance_id));
    }

    {
        // Prepare transaction
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id);
        meta_service->prepare_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
    }

    {
        // Commit partition
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id);
        meta_service->commit_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
    }

    auto txn_kv = meta_service->txn_kv();
    Versionstamp version1;
    {
        // Verify partition meta/index/inverted indexes are exists
        std::string partition_meta_key = versioned::meta_partition_key({instance_id, partition_id});
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
        std::string value;
        ASSERT_EQ(versioned_get(txn.get(), partition_meta_key, &version1, &value),
                  TxnErrorCode::TXN_OK);

        std::string partition_inverted_index_key = versioned::partition_inverted_index_key(
                {instance_id, db_id, table_id, partition_id});
        ASSERT_EQ(txn->get(partition_inverted_index_key, &value), TxnErrorCode::TXN_OK);

        std::string partition_index_key =
                versioned::partition_index_key({instance_id, partition_id});
        ASSERT_EQ(txn->get(partition_index_key, &value), TxnErrorCode::TXN_OK);
        PartitionIndexPB partition_index;
        ASSERT_TRUE(partition_index.ParseFromString(value));
        ASSERT_EQ(partition_index.db_id(), db_id);
        ASSERT_EQ(partition_index.table_id(), table_id);
    }

    Versionstamp version2;
    {
        // Verify table version exists
        MetaReader meta_reader(instance_id, txn_kv.get());
        ASSERT_EQ(meta_reader.get_table_version(table_id, &version2), TxnErrorCode::TXN_OK);
    }

    ASSERT_EQ(version1, version2);

    {
        // verify commit partition log
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
        std::string log_key = versioned::log_key({instance_id});
        std::string value;
        ASSERT_EQ(versioned_get(txn.get(), log_key, &version2, &value), TxnErrorCode::TXN_OK);
        OperationLogPB operation_log;
        ASSERT_TRUE(operation_log.ParseFromString(value));
        ASSERT_TRUE(operation_log.has_commit_partition());
    }

    ASSERT_EQ(version1, version2);
}

TEST(MetaServiceOperationLogTest, DropPartitionLog) {
    auto meta_service = get_meta_service(false);
    std::string instance_id = "commit_partition_log";
    auto* sp = SyncPoint::get_instance();
    DORIS_CLOUD_DEFER {
        SyncPoint::get_instance()->clear_all_call_backs();
    };
    sp->set_call_back("get_instance_id", [&](auto&& args) {
        auto* ret = try_any_cast_ret<std::string>(args);
        ret->first = instance_id;
        ret->second = true;
    });
    sp->enable_processing();

    constexpr int64_t db_id = 123;
    constexpr int64_t table_id = 10001;
    constexpr int64_t index_id = 10002;
    constexpr int64_t partition_id = 10003;

    {
        // write instance
        InstanceInfoPB instance_info;
        instance_info.set_instance_id(instance_id);
        instance_info.set_multi_version_status(MULTI_VERSION_WRITE_ONLY);
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(meta_service->txn_kv()->create_txn(&txn), TxnErrorCode::TXN_OK);
        txn->put(instance_key(instance_id), instance_info.SerializeAsString());
        ASSERT_EQ(txn->commit(), TxnErrorCode::TXN_OK);

        meta_service->resource_mgr()->refresh_instance(instance_id);
        ASSERT_TRUE(meta_service->resource_mgr()->is_version_write_enabled(instance_id));
    }

    {
        // Prepare partition 0,1,2,3
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id);
        req.add_partition_ids(partition_id + 1);
        req.add_partition_ids(partition_id + 2);
        req.add_partition_ids(partition_id + 3);
        meta_service->prepare_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
    }

    {
        // Commit partition 2,3
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id + 2);
        req.add_partition_ids(partition_id + 3);
        meta_service->commit_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
    }

    auto txn_kv = meta_service->txn_kv();
    size_t num_logs = count_range(txn_kv.get(), versioned::log_key(instance_id),
                                  versioned::log_key(instance_id) + "\xFF");

    {
        // Drop partition 0
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id);
        meta_service->drop_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();

        // No operation log are generated for drop partition 0 (it is not committed).
        size_t new_num_logs = count_range(txn_kv.get(), versioned::log_key(instance_id),
                                          versioned::log_key(instance_id) + "\xFF");
        ASSERT_EQ(new_num_logs, num_logs)
                << "Expected no new operation logs for drop partition 0, but found "
                << new_num_logs - num_logs << dump_range(txn_kv.get());

        // The recycle partition key must exists.
        std::string recycle_key = recycle_partition_key({instance_id, partition_id});
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
        std::string value;
        TxnErrorCode err = txn->get(recycle_key, &value);
        ASSERT_EQ(err, TxnErrorCode::TXN_OK);
    }

    {
        // Drop partition 1,2, it should generate operation logs.
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id + 1);
        req.add_partition_ids(partition_id + 2);
        req.set_need_update_table_version(true);
        meta_service->drop_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
        // Check that new operation logs are generated.
        size_t new_num_logs = count_range(txn_kv.get(), versioned::log_key(instance_id),
                                          versioned::log_key(instance_id) + "\xFF");
        ASSERT_GT(new_num_logs, num_logs)
                << "Expected new operation logs for drop partition 1,2, but found "
                << new_num_logs - num_logs << dump_range(txn_kv.get());
        num_logs = new_num_logs;
    }

    {
        // Drop partition 3, it should generate operation logs.
        brpc::Controller ctrl;
        PartitionRequest req;
        PartitionResponse res;
        req.set_db_id(db_id);
        req.set_table_id(table_id);
        req.add_index_ids(index_id);
        req.add_partition_ids(partition_id + 3);
        req.set_need_update_table_version(true);
        meta_service->drop_partition(&ctrl, &req, &res, nullptr);
        ASSERT_EQ(res.status().code(), MetaServiceCode::OK) << res.status().DebugString();
        // Check that new operation logs are generated.
        size_t new_num_logs = count_range(txn_kv.get(), versioned::log_key(instance_id),
                                          versioned::log_key(instance_id) + "\xFF");
        ASSERT_GT(new_num_logs, num_logs)
                << "Expected new operation logs for drop partition 3, but found "
                << new_num_logs - num_logs << dump_range(txn_kv.get());
        num_logs = new_num_logs;

        // The recycle partition key should not be exists.
        std::string recycle_key = recycle_partition_key({instance_id, partition_id + 3});
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
        std::string value;
        TxnErrorCode err = txn->get(recycle_key, &value);
        ASSERT_EQ(err, TxnErrorCode::TXN_KEY_NOT_FOUND)
                << "Expected recycle partition key to not exist, but found it: " << hex(recycle_key)
                << " with value: " << escape_hex(value);
    }

    Versionstamp version1;
    Versionstamp version2;
    {
        // Verify table version exists
        MetaReader meta_reader(instance_id, txn_kv.get());
        ASSERT_EQ(meta_reader.get_table_version(table_id, &version1), TxnErrorCode::TXN_OK);
    }

    {
        // verify last drop partition log
        std::unique_ptr<Transaction> txn;
        ASSERT_EQ(txn_kv->create_txn(&txn), TxnErrorCode::TXN_OK);
        std::string log_key = versioned::log_key({instance_id});
        std::string value;
        ASSERT_EQ(versioned_get(txn.get(), log_key, &version2, &value), TxnErrorCode::TXN_OK);
        OperationLogPB operation_log;
        ASSERT_TRUE(operation_log.ParseFromString(value));
        ASSERT_TRUE(operation_log.has_drop_partition());
        ASSERT_EQ(operation_log.drop_partition().partition_ids_size(), 1);
        ASSERT_EQ(operation_log.drop_partition().partition_ids(0), partition_id + 3);
    }

    ASSERT_EQ(version1, version2);
}

} // namespace doris::cloud

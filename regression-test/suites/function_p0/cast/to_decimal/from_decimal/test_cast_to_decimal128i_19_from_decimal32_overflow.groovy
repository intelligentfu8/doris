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


suite("test_cast_to_decimal128i_19_from_decimal32_overflow") {

    // This test case is generated from the correspoinding be UT test case,
    // update this case if the correspoinding be UT test case is updated,
    // e.g.: ../run-be-ut.sh --run --filter=FunctionCastToDecimalTest.* --gen_regression_case
    sql "drop table if exists test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20;"
    sql "create table test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20(f1 int, f2 decimalv3(9, 0)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20 values (0, "10"),(1, "999999998"),(2, "999999999");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20_data_start_index = 0
    def test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20_data_start_index; data_index < test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 18)) from test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_20_non_strict 'select f1, cast(f2 as decimalv3(19, 18)) from test_cast_to_decimal_19_18_from_decimal_9_0_overflow_20 order by 1;'

    sql "drop table if exists test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21;"
    sql "create table test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21(f1 int, f2 decimalv3(9, 1)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21 values (0, "10.9"),(1, "99999998.9"),(2, "99999999.9");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21_data_start_index = 0
    def test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21_data_start_index; data_index < test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 18)) from test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_21_non_strict 'select f1, cast(f2 as decimalv3(19, 18)) from test_cast_to_decimal_19_18_from_decimal_9_1_overflow_21 order by 1;'

    sql "drop table if exists test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24;"
    sql "create table test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24(f1 int, f2 decimalv3(1, 0)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24 values (0, "1"),(1, "8"),(2, "9");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24_data_start_index = 0
    def test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24_data_start_index; data_index < test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_24_non_strict 'select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_1_0_overflow_24 order by 1;'

    sql "drop table if exists test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26;"
    sql "create table test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26(f1 int, f2 decimalv3(9, 0)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26 values (0, "1"),(1, "999999998"),(2, "999999999");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26_data_start_index = 0
    def test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26_data_start_index; data_index < test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_26_non_strict 'select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_0_overflow_26 order by 1;'

    sql "drop table if exists test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27;"
    sql "create table test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27(f1 int, f2 decimalv3(9, 1)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27 values (0, "1.9"),(1, "99999998.9"),(2, "99999999.9");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27_data_start_index = 0
    def test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27_data_start_index; data_index < test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_27_non_strict 'select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_1_overflow_27 order by 1;'

    sql "drop table if exists test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28;"
    sql "create table test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28(f1 int, f2 decimalv3(9, 8)) properties('replication_num'='1');"
    sql """insert into test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28 values (0, "1.99999999"),(1, "8.99999999"),(2, "9.99999999");
    """

    sql "set enable_strict_cast=true;"

    def test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28_data_start_index = 0
    def test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28_data_end_index = 3
    for (int data_index = test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28_data_start_index; data_index < test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28_data_end_index; data_index++) {
        test {
            sql "select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28 where f1 = ${data_index}"
            exception ""
        }
    }
    sql "set enable_strict_cast=false;"
    qt_sql_28_non_strict 'select f1, cast(f2 as decimalv3(19, 19)) from test_cast_to_decimal_19_19_from_decimal_9_8_overflow_28 order by 1;'

}
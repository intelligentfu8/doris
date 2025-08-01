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

#include <glog/logging.h>
#include <re2/re2.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <iterator>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>

#include "common/exception.h"
#include "common/status.h"
#include "runtime/define_primitive_type.h"
#include "util/hash_util.hpp"
#include "util/time_lut.h"
#include "util/timezone_utils.h"

namespace cctz {
class time_zone;
} // namespace cctz

namespace doris::vectorized {
class DataTypeDateTime;
class DataTypeDateV2;
class DataTypeDateTimeV2;
} // namespace doris::vectorized

namespace doris {
#include "common/compile_check_avoid_begin.h"
enum TimeUnit {
    MICROSECOND,
    MILLISECOND,
    SECOND,
    MINUTE,
    HOUR,
    DAY,
    WEEK,
    MONTH,
    QUARTER,
    YEAR,
    SECOND_MICROSECOND,
    MINUTE_MICROSECOND,
    MINUTE_SECOND,
    HOUR_MICROSECOND,
    HOUR_SECOND,
    HOUR_MINUTE,
    DAY_MICROSECOND,
    DAY_SECOND,
    DAY_MINUTE,
    DAY_HOUR,
    YEAR_MONTH
};

struct TimeInterval {
    int64_t year;
    int64_t month;
    int64_t day;
    int64_t hour;
    int64_t minute;
    int64_t second;
    int64_t millisecond;
    int64_t microsecond;
    bool is_neg;

    TimeInterval()
            : year(0),
              month(0),
              day(0),
              hour(0),
              minute(0),
              second(0),
              millisecond(0),
              microsecond(0),
              is_neg(false) {}

    TimeInterval(TimeUnit unit, int64_t count, bool is_neg_param)
            : year(0),
              month(0),
              day(0),
              hour(0),
              minute(0),
              second(0),
              millisecond(0),
              microsecond(0),
              is_neg(is_neg_param) {
        switch (unit) {
        case YEAR:
            year = count;
            break;
        case MONTH:
            month = count;
            break;
        case WEEK:
            day = 7 * count;
            break;
        case DAY:
            day = count;
            break;
        case HOUR:
            hour = count;
            break;
        case MINUTE:
            minute = count;
            break;
        case SECOND:
            second = count;
            break;
        case SECOND_MICROSECOND:
            microsecond = count;
            break;
        case MILLISECOND:
            millisecond = count;
            break;
        case MICROSECOND:
            microsecond = count;
            break;
        default:
            break;
        }
    }
};

enum TimeType { TIME_TIME = 1, TIME_DATE = 2, TIME_DATETIME = 3 };

constexpr int SAFE_FORMAT_STRING_MARGIN = 12;

// Used to compute week
const int WEEK_MONDAY_FIRST = 1;
const int WEEK_YEAR = 2;
const int WEEK_FIRST_WEEKDAY = 4;

// 9999-99-99 99:99:99; 19 + 1('\0')
const int MAX_DTVALUE_STR_LEN = 20;

const int DATE_MAX_DAYNR = 3652424;
// two-digit years < this are 20..; >= this are 19..
const int YY_PART_YEAR = 70;

// Limits of time value
const int TIME_MAX_HOUR = 256;
const int TIME_MAX_MINUTE = 59;
const int TIME_MAX_SECOND = 59;
const int TIME_MAX_VALUE = 10000 * TIME_MAX_HOUR + 100 * TIME_MAX_MINUTE + TIME_MAX_SECOND;
const int TIME_MAX_VALUE_SECONDS = 3600 * TIME_MAX_HOUR + 60 * TIME_MAX_MINUTE + TIME_MAX_SECOND;

constexpr int HOUR_PER_DAY = 24;
constexpr int64_t SECOND_PER_HOUR = 3600;
constexpr int64_t SECOND_PER_MINUTE = 60;
constexpr int64_t MS_PER_SECOND = 1000 * 1000;

inline constexpr int S_DAYS_IN_MONTH[13] = {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

constexpr size_t const_length(const char* str) {
    return (str == nullptr || *str == 0) ? 0 : const_length(str + 1) + 1;
}

constexpr size_t max_char_length(const char* const* name, size_t end) {
    size_t res = 0;
    for (int i = 0; i < end; ++i) {
        res = std::max(const_length(name[i]), res);
    }
    return res;
}

static constexpr const char* s_month_name[] = {
        "",     "January", "February",  "March",   "April",    "May",      "June",
        "July", "August",  "September", "October", "November", "December", nullptr};

static constexpr const char* s_day_name[] = {"Monday", "Tuesday",  "Wednesday", "Thursday",
                                             "Friday", "Saturday", "Sunday",    nullptr};

static constexpr size_t MAX_DAY_NAME_LEN = max_char_length(s_day_name, std::size(s_day_name));
static constexpr size_t MAX_MONTH_NAME_LEN = max_char_length(s_month_name, std::size(s_month_name));

static constexpr uint8_t TIME_PART_LENGTH = 37;

static constexpr uint32_t MAX_DATE_V2 = 31 | (12 << 5) | (9999 << 9);
static constexpr uint32_t MIN_DATE_V2 = 1 | (1 << 5);

static constexpr uint64_t MAX_DATETIME_V2 = ((uint64_t)MAX_DATE_V2 << TIME_PART_LENGTH) |
                                            ((uint64_t)23 << 32) | ((uint64_t)59 << 26) |
                                            ((uint64_t)59 << 20) | 999999;
static constexpr uint64_t MIN_DATETIME_V2 = (uint64_t)MIN_DATE_V2 << TIME_PART_LENGTH;

static constexpr uint32_t MAX_YEAR = 9999;
static constexpr uint32_t MAX_MONTH = 12;
static constexpr uint32_t MAX_HOUR = 23;
static constexpr uint32_t MAX_MINUTE = 59;
static constexpr uint32_t MAX_SECOND = 59;
static constexpr uint32_t MAX_MICROSECOND = 999999;

static constexpr uint32_t DATEV2_YEAR_WIDTH = 23;
static constexpr uint32_t DATETIMEV2_YEAR_WIDTH = 18;
static constexpr uint32_t DATETIMEV2_MONTH_WIDTH = 4;

static RE2 time_zone_offset_format_reg(R"(^[+-]{1}\d{2}\:\d{2}$)");

uint8_t mysql_week_mode(uint32_t mode);

inline uint32_t calc_daynr(uint16_t year, uint8_t month, uint8_t day);

struct DateV2ValueType {
    uint32_t day_ : 5;
    uint32_t month_ : 4;
    uint32_t year_ : 23;

    DateV2ValueType(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute,
                    uint8_t second, uint32_t microsecond)
            : day_(day), month_(month), year_(year) {}
};

struct DateTimeV2ValueType {
    uint64_t microsecond_ : 20;
    uint64_t second_ : 6;
    uint64_t minute_ : 6;
    uint64_t hour_ : 5;
    uint64_t day_ : 5;
    uint64_t month_ : 4;
    uint64_t year_ : 18;

    DateTimeV2ValueType(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute,
                        uint8_t second, uint32_t microsecond)
            : microsecond_(microsecond),
              second_(second),
              minute_(minute),
              hour_(hour),
              day_(day),
              month_(month),
              year_(year) {}
};

template <typename T>
class DateV2Value;

class VecDateTimeValue { // Now this type is a temp solution with little changes, maybe large refactoring follow-up.
public:
    // Constructor
    VecDateTimeValue()
            : _neg(0),
              _type(TIME_DATETIME),
              _second(0),
              _minute(0),
              _hour(0),
              _day(0), // _microsecond(0): remove it to reduce memory, and Reorder the variables
              _month(0), // so this is a difference between Vectorization mode and Rowbatch mode with DateTimeValue;
              _year(0) {} // before int128  16 bytes  --->  after int64 8 bytes

    const static VecDateTimeValue FIRST_DAY;

    // The data format of DATE/DATETIME is different in storage layer and execute layer.
    // So we should use different creator to get data from value.
    // We should use create_from_olap_xxx only at binary data scanned from storage engine and convert to typed data.
    // At other case, we just use binary_cast<Int64, VecDateTimeValue>.

    // olap storage layer date data format:
    // 64 bits binary data [year(remaining bits), month(4 bits), day(5 bits)]
    // execute layer date/datetime and olap storage layer datetime data format:
    // 8 bytes integer data [year(remaining digits), month(2 digits), day(2 digits), hour(2 digits), minute(2 digits) ,second(2 digits)]

    static VecDateTimeValue create_from_olap_date(uint64_t value) {
        VecDateTimeValue date;
        date.from_olap_date(value);
        return date;
    }

    static VecDateTimeValue create_from_olap_datetime(uint64_t value) {
        VecDateTimeValue datetime;
        datetime.from_olap_datetime(value);
        return datetime;
    }

    template <typename T>
    void create_from_date_v2(DateV2Value<T>& value, TimeType type);

    template <typename T>
    void create_from_date_v2(DateV2Value<T>&& value, TimeType type);

    // Converted from Olap Date or Datetime
    bool from_olap_datetime(uint64_t datetime) {
        _neg = 0;
        _type = TIME_DATETIME;
        uint64_t date = datetime / 1000000;
        uint64_t time = datetime % 1000000;

        auto [year, month, day, hour, minute, second] = std::tuple {0, 0, 0, 0, 0, 0};
        year = date / 10000;
        date %= 10000;
        month = date / 100;
        day = date % 100;
        hour = time / 10000;
        time %= 10000;
        minute = time / 100;
        second = time % 100;

        return check_range_and_set_time(year, month, day, hour, minute, second, _type);
    }

    uint64_t to_olap_datetime() const {
        uint64_t date_val = _year * 10000 + _month * 100 + _day;
        uint64_t time_val = _hour * 10000 + _minute * 100 + _second;
        return date_val * 1000000 + time_val;
    }

    bool from_olap_date(uint64_t date) {
        _neg = 0;
        _type = TIME_DATE;

        auto [year, month, day, hour, minute, second] = std::tuple {0, 0, 0, 0, 0, 0};

        day = date & 0x1f;
        date >>= 5;
        month = date & 0x0f;
        date >>= 4;
        year = date;

        return check_range_and_set_time(year, month, day, hour, minute, second, _type);
    }

    //note(wb) not check in this method
    void inline set_olap_date(uint64_t olap_date_val) {
        _neg = 0;
        _type = TIME_DATE;

        _day = olap_date_val & 0x1f;
        _month = (olap_date_val >> 5) & 0x0f;
        _year = olap_date_val >> 9;
        _hour = 0;
        _minute = 0;
        _second = 0;
    }

    uint64_t to_olap_date() const {
        uint64_t val;
        val = _year;
        val <<= 4;
        val |= _month;
        val <<= 5;
        val |= _day;
        return val;
    }

    bool from_date_format_str(const char* format, int format_len, const char* value,
                              int64_t value_len) {
        memset(this, 0, sizeof(*this));
        return from_date_format_str(format, format_len, value, value_len, nullptr);
    }

    operator int64_t() const { return to_int64(); }

    // Given days since 0000-01-01, construct the datetime value.
    bool from_date_daynr(uint64_t);

    // Construct Date/Datetime type value from string.
    // At least the following formats are recognised (based on number of digits)
    // 'YYMMDD', 'YYYYMMDD', 'YYMMDDHHMMSS', 'YYYYMMDDHHMMSS'
    // 'YY-MM-DD', 'YYYY-MM-DD', 'YY-MM-DD HH.MM.SS'
    // 'YYYYMMDDTHHMMSS'
    bool from_date_str(const char* str, size_t len);
    bool from_date_str(const char* str, size_t len, const cctz::time_zone& local_time_zone);

    // Construct Date/Datetime type value from int64_t value.
    // Return true if convert success. Otherwise return false.
    bool from_date_int64(int64_t value);

    bool from_date(int64_t value) { return from_date_int64(value); }

    // Convert this value to string
    // this will check type to decide which format to convert
    // TIME:  format 'hh:mm:ss.xxxxxx'
    // DATE:  format 'YYYY-MM-DD'
    // DATETIME:  format 'YYYY-MM-DD hh:mm:ss.xxxxxx'
    int32_t to_buffer(char* buffer) const;

    char* to_string(char* to) const;

    // Convert this datetime value to string by the format string.
    // for performance of checking, may return false when just APPROACH BUT NOT REACH max_valid_length.
    // so need a little big buffer and its length as max_valid_length to make sure store valid data.
    // to make sure of this. make the buffer size = <data_need_length> + SAFE_FORMAT_STRING_MARGIN. and pass this size as max_valid_length
    bool to_format_string_conservative(const char* format, size_t len, char* to,
                                       size_t max_valid_length) const;

    // compute the length of data format pattern
    static int compute_format_len(const char* format, size_t len);

    // Return true if range or date is invalid
    static bool check_range(uint32_t year, uint32_t month, uint32_t day, uint32_t hour,
                            uint32_t minute, uint32_t second, uint16_t type);

    static bool check_date(uint32_t year, uint32_t month, uint32_t day);

    // Convert this value to uint64_t
    // Will check its type
    int64_t to_int64() const;

    [[nodiscard]] bool check_range_and_set_time(uint32_t year, uint32_t month, uint32_t day,
                                                uint32_t hour, uint32_t minute, uint32_t second,
                                                uint16_t type) {
        if (check_range(year, month, day, hour, minute, second, type)) [[unlikely]] {
            return false;
        }
        unchecked_set_time(year, month, day, hour, minute, second);
        return true;
    }

    // for date type, when we cast_to_date, the time part will be reset to 0. so dont worry.
    template <TimeUnit unit>
    [[nodiscard]] bool set_time_unit(uint32_t val) {
        // is uint so need check upper bound only
        if constexpr (unit == TimeUnit::YEAR) {
            if (val > MAX_YEAR) [[unlikely]] {
                return false;
            }
            _year = val;
        } else if constexpr (unit == TimeUnit::MONTH) {
            if (val > MAX_MONTH || !val) [[unlikely]] {
                return false;
            }
            _month = val;
        } else if constexpr (unit == TimeUnit::DAY) {
            DCHECK(_month <= MAX_MONTH);
            DCHECK(_month != 0);
            if (!val ||
                (val > S_DAYS_IN_MONTH[_month] && (!is_leap(_year) || _month != 2 || val != 29))) {
                return false;
            }
            _day = val;
        } else if constexpr (unit == TimeUnit::HOUR) {
            if (val > MAX_HOUR) [[unlikely]] {
                return false;
            }
            _hour = val;
        } else if constexpr (unit == TimeUnit::MINUTE) {
            if (val > MAX_MINUTE) [[unlikely]] {
                return false;
            }
            _minute = val;
        } else if constexpr (unit == TimeUnit::SECOND) {
            if (val > MAX_SECOND) [[unlikely]] {
                return false;
            }
            _second = val;
        } else {
            static_assert(unit == TimeUnit::YEAR, "Unsupported TimeUnit in set_time_unit");
            __builtin_unreachable();
        }
        return true;
    }

    template <TimeUnit unit>
    void unchecked_set_time_unit(uint32_t val) {
        // is uint so need check upper bound only
        if constexpr (unit == TimeUnit::YEAR) {
            _year = val;
        } else if constexpr (unit == TimeUnit::MONTH) {
            _month = val;
        } else if constexpr (unit == TimeUnit::DAY) {
            _day = val;
        } else if constexpr (unit == TimeUnit::HOUR) {
            _hour = val;
        } else if constexpr (unit == TimeUnit::MINUTE) {
            _minute = val;
        } else if constexpr (unit == TimeUnit::SECOND) {
            _second = val;
        } else {
            static_assert(unit == TimeUnit::YEAR,
                          "Unsupported TimeUnit in unchecked_set_time_unit");
            __builtin_unreachable();
        }
    }

    void unchecked_set_time(uint32_t year, uint32_t month, uint32_t day, uint32_t hour,
                            uint32_t minute, uint32_t second);

    uint32_t daynr() const { return calc_daynr(_year, _month, _day); }

    uint16_t year() const { return _year; }
    uint8_t month() const { return _month; }
    int quarter() const { return (_month - 1) / 3 + 1; }
    int week() const { return week(mysql_week_mode(0)); } //00-53
    uint8_t day() const { return _day; }
    uint8_t hour() const { return _hour; }
    uint8_t minute() const { return _minute; }
    uint16_t second() const { return _second; }

    int64_t time_part_to_seconds() const {
        return _hour * SECOND_PER_HOUR + _minute * SECOND_PER_MINUTE + _second;
    }

    void reset_time_part() {
        _hour = 0;
        _minute = 0;
        _second = 0;
    }

    bool check_loss_accuracy_cast_to_date() {
        auto loss_accuracy = _hour != 0 || _minute != 0 || _second != 0;
        cast_to_date();
        return loss_accuracy;
    }

    void cast_to_date() {
        _hour = 0;
        _minute = 0;
        _second = 0;
        _type = TIME_DATE;
    }

    void to_datetime() { _type = TIME_DATETIME; }

    // Weekday, from 0(Mon) to 6(Sun)
    uint8_t weekday() const { return calc_weekday(daynr(), false); }
    auto day_of_week() const { return (weekday() + 1) % 7 + 1; }

    // The bits in week_format has the following meaning:
    // WEEK_MONDAY_FIRST (0)
    //  If not set:
    //      Sunday is first day of week
    //  If set:
    //      Monday is first day of week
    //
    // WEEK_YEAR (1)
    //  If not set:
    //      Week is in range 0-53
    //      Week 0 is returned for the last week of the previous year (for
    //      a date at start of january) In this case one can get 53 for the
    //      first week of next year.  This flag ensures that the week is
    //      relevant for the given year. Note that this flag is only
    //      relevant if WEEK_JANUARY is not set.
    //  If set:
    //      Week is in range 1-53.
    //      In this case one may get week 53 for a date in January (when
    //      the week is that last week of previous year) and week 1 for a
    //      date in December.
    //
    // WEEK_FIRST_WEEKDAY (2)
    //  If not set
    //      Weeks are numbered according to ISO 8601:1988
    //  If set
    //      The week that contains the first 'first-day-of-week' is week 1.
    //
    // ISO 8601:1988 means that
    //      if the week containing January 1 has
    //      four or more days in the new year, then it is week 1;
    //      Otherwise it is the last week of the previous year, and the
    //      next week is week 1.
    uint8_t week(uint8_t) const;

    uint32_t year_week(uint8_t mode) const;

    // Add interval
    template <TimeUnit unit, bool need_check = true>
    bool date_add_interval(const TimeInterval& interval);

    // set interval
    template <TimeUnit unit>
    bool date_set_interval(const TimeInterval& interval);

    template <TimeUnit unit>
    bool datetime_trunc(); //datetime trunc, like trunc minute = 0

    //unix_timestamp is called with a timezone argument,
    //it returns seconds of the value of date literal since '1970-01-01 00:00:00' UTC
    bool unix_timestamp(int64_t* timestamp, const std::string& timezone) const;
    bool unix_timestamp(int64_t* timestamp, const cctz::time_zone& ctz) const;

    //construct datetime_value from timestamp and timezone
    //timestamp is an internal timestamp value representing seconds since '1970-01-01 00:00:00' UTC. negative avaliable.
    //we don't do any check in it because it's hot path. any usage want ensure the time legality should check itself.
    bool from_unixtime(int64_t, const std::string& timezone);
    void from_unixtime(int64_t, const cctz::time_zone& ctz);

    bool operator==(const VecDateTimeValue& other) const {
        // NOTE: This is not same with MySQL.
        // MySQL convert both to int with left value type and then compare
        // We think all fields equals.
        int64_t v1 = to_int64_datetime_packed();
        int64_t v2 = other.to_int64_datetime_packed();
        return v1 == v2;
    }

    bool operator!=(const VecDateTimeValue& other) const { return !(*this == other); }

    // Now, we don't support TIME_TIME type,
    bool operator<=(const VecDateTimeValue& other) const { return !(*this > other); }

    bool operator>=(const VecDateTimeValue& other) const { return !(*this < other); }

    bool operator<(const VecDateTimeValue& other) const {
        int64_t v1 = to_int64_datetime_packed();
        int64_t v2 = other.to_int64_datetime_packed();
        return v1 < v2;
    }

    bool operator>(const VecDateTimeValue& other) const {
        int64_t v1 = to_int64_datetime_packed();
        int64_t v2 = other.to_int64_datetime_packed();
        return v1 > v2;
    }

    template <typename T>
    bool operator==(const DateV2Value<T>& other) const;

    template <typename T>
    bool operator!=(const DateV2Value<T>& other) const {
        return !(*this == other);
    }

    template <typename T>
    bool operator<=(const DateV2Value<T>& other) const;

    template <typename T>
    bool operator>=(const DateV2Value<T>& other) const;

    template <typename T>
    bool operator<(const DateV2Value<T>& other) const;

    template <typename T>
    bool operator>(const DateV2Value<T>& other) const;

    const char* month_name() const;

    const char* day_name() const;

    VecDateTimeValue& operator+=(int64_t count) {
        bool is_neg = false;
        if (count < 0) {
            is_neg = true;
            count = -count;
        }
        switch (_type) {
        case TIME_DATE: {
            TimeInterval interval(DAY, count, is_neg);
            date_add_interval<DAY>(interval);
            break;
        }
        case TIME_DATETIME: {
            TimeInterval interval(SECOND, count, is_neg);
            date_add_interval<SECOND>(interval);
            break;
        }
        case TIME_TIME: {
            TimeInterval interval(SECOND, count, is_neg);
            date_add_interval<SECOND>(interval);
            break;
        }
        }
        return *this;
    }

    VecDateTimeValue& operator++() { return *this += 1; }

    VecDateTimeValue& operator--() { return *this += -1; }

    uint32_t to_date_v2() const { return (year() << 9 | month() << 5 | day()); }

    uint64_t to_datetime_v2() const {
        return (uint64_t)(((uint64_t)year() << 46) | ((uint64_t)month() << 42) |
                          ((uint64_t)day() << 37) | ((uint64_t)hour() << 32) |
                          ((uint64_t)minute() << 26) | ((uint64_t)second() << 20));
    }

    uint32_t hash(int seed) const { return HashUtil::hash(this, sizeof(*this), seed); }

    int day_of_year() const { return daynr() - calc_daynr(_year, 1, 1) + 1; }

    // TODO(zhaochun): local time ???
    static VecDateTimeValue local_time();

    static VecDateTimeValue datetime_min_value() {
        static VecDateTimeValue _s_min_datetime_value(0, TIME_DATETIME, 0, 0, 0, 0, 1, 1);
        return _s_min_datetime_value;
    }

    static VecDateTimeValue datetime_max_value() {
        static VecDateTimeValue _s_max_datetime_value(0, TIME_DATETIME, 23, 59, 59, 9999, 12, 31);
        return _s_max_datetime_value;
    }

    template <typename T>
    int64_t time_part_diff(const T& rhs) const {
        return time_part_to_seconds() - rhs.time_part_to_seconds();
    }

    template <typename T>
    int64_t datetime_diff_in_seconds(const T& rhs) const {
        return (daynr() - rhs.daynr()) * SECOND_PER_HOUR * HOUR_PER_DAY + time_part_diff(rhs);
    }

    void set_type(int type);

    int type() const { return _type; }

    bool is_valid_date() const {
        return !check_range(_year, _month, _day, _hour, _minute, _second, _type) && _month > 0 &&
               _day > 0;
    }

    int64_t to_datetime_int64() const;

    // To compatible with MySQL
    int64_t to_int64_datetime_packed() const {
        int64_t ymd = ((_year * 13 + _month) << 5) | _day;
        int64_t hms = (_hour << 12) | (_minute << 6) | _second;
        int64_t tmp = make_packed_time(((ymd << 17) | hms), 0);
        return _neg ? -tmp : tmp;
    }

    void from_packed_time(int64_t packed_time) {
        int64_t ymdhms = packed_time >> 24;
        int64_t ymd = ymdhms >> 17;
        int64_t hms = ymdhms % (1 << 17);

        _day = ymd % (1 << 5);
        int64_t ym = ymd >> 5;
        _month = ym % 13;
        _year = ym / 13;
        _year %= 10000;
        _second = hms % (1 << 6);
        _minute = (hms >> 6) % (1 << 6);
        _hour = (hms >> 12);
        _neg = 0;
        _type = TIME_DATETIME;
    }

    bool get_date_from_daynr(uint64_t);

    // reset 0
    void reset_zero_by_type(int type) { set_zero(type); }

private:
    // Used to make sure sizeof VecDateTimeValue
    friend class UnusedClass;

    int64_t make_packed_time(int64_t time, int64_t second_part) const {
        return (time << 24) + second_part;
    }

    // Used to construct from int value
    int64_t standardize_timevalue(int64_t value);

    // Used to convert to a string.
    char* append_date_buffer(char* to) const;
    char* append_time_buffer(char* to) const;
    char* to_datetime_buffer(char* to) const;
    char* to_date_buffer(char* to) const;
    char* to_time_buffer(char* to) const;

    bool from_date_str_base(const char* date_str, size_t len,
                            const cctz::time_zone* local_time_zone);

    int64_t to_date_int64() const;
    int64_t to_time_int64() const;

    static uint8_t calc_week(const VecDateTimeValue& value, uint8_t mode, uint32_t* year,
                             bool disable_lut = false);

    // Helper to set max, min, zero
    void set_zero(int type);
    void set_max_time(bool neg);

    bool from_date_format_str(const char* format, int format_len, const char* value,
                              int64_t value_len, const char** sub_val_end);

    // 1 bits for neg. 3 bits for type. 12bit for second
    uint16_t _neg : 1;  // Used for time value.
    uint16_t _type : 3; // Which type of this value.
    uint16_t _second : 12;
    uint8_t _minute;
    uint8_t _hour;
    uint8_t _day;
    uint8_t _month;
    uint16_t _year;

    VecDateTimeValue(uint8_t neg, uint8_t type, uint8_t hour, uint8_t minute, uint8_t second,
                     uint16_t year, uint8_t month, uint8_t day)
            : _neg(neg),
              _type(type),
              _second(second),
              _minute(minute),
              _hour(hour),
              _day(day),
              _month(month),
              _year(year) {}
};

inline const VecDateTimeValue VecDateTimeValue::FIRST_DAY(false, TYPE_DATETIME, 0, 0, 0, 1, 1, 1);

template <typename T>
class DateV2Value {
public:
    static constexpr bool is_datetime = std::is_same_v<T, DateTimeV2ValueType>;
    using underlying_value = std::conditional_t<is_datetime, uint64_t, uint32_t>;

    // Constructor
    DateV2Value() : date_v2_value_(0, 0, 0, 0, 0, 0, 0) {}

    DateV2Value(underlying_value int_val) : int_val_(int_val) {}
    template <typename U>
        requires std::is_integral_v<U>
    DateV2Value(U other) = delete;

    DateV2Value(DateV2Value<T>& other) = default;

    DateV2Value(const DateV2Value<T>& other) = default;

    const static DateV2Value<T> FIRST_DAY;

    static DateV2Value create_from_olap_date(uint64_t value) {
        DateV2Value<T> date;
        date.from_olap_date(value);
        return date;
    }

    static DateV2Value create_from_olap_datetime(uint64_t value) {
        DateV2Value<T> datetime;
        datetime.from_olap_datetime(value);
        return datetime;
    }

    void set_microsecond(uint64_t microsecond);

    bool from_olap_date(uint64_t date) {
        auto [year, month, day] = std::tuple {0, 0, 0};

        day = date & 0x1f;
        date >>= 5;
        month = date & 0x0f;
        date >>= 4;
        year = date;

        return check_range_and_set_time(year, month, day, 0, 0, 0, 0);
    }

    bool from_olap_datetime(uint64_t datetime) {
        uint64_t date = datetime / 1000000;
        uint64_t time = datetime % 1000000;

        auto [year, month, day, hour, minute, second] = std::tuple {0, 0, 0, 0, 0, 0};
        year = date / 10000;
        date %= 10000;
        month = date / 100;
        day = date % 100;
        hour = time / 10000;
        time %= 10000;
        minute = time / 100;
        second = time % 100;

        return check_range_and_set_time(year, month, day, hour, minute, second, 0);
    }

    uint64_t to_olap_date() const {
        uint64_t val;
        val = date_v2_value_.year_;
        val <<= 4;
        val |= date_v2_value_.month_;
        val <<= 5;
        val |= date_v2_value_.day_;
        return val;
    }

    // Convert this datetime value to string by the format string.
    // for performance of checking, may return false when just APPROACH BUT NOT REACH max_valid_length.
    // so need a little big buffer and its length as max_valid_length to make sure store valid data.
    // to make sure of this. make the buffer size = <data_need_length> + SAFE_FORMAT_STRING_MARGIN. and pass this size as max_valid_length
    bool to_format_string_conservative(const char* format, size_t len, char* to,
                                       size_t max_valid_length) const;

    bool from_date_format_str(const char* format, size_t format_len, const char* value,
                              size_t value_len) {
        return from_date_format_str(format, format_len, value, value_len, nullptr);
    }

    template <typename U>
    void assign_from(DateV2Value<U> src) {
        date_v2_value_.year_ = src.year();
        date_v2_value_.month_ = src.month();
        date_v2_value_.day_ = src.day();
        if constexpr (is_datetime && std::is_same_v<U, DateTimeV2ValueType>) {
            date_v2_value_.hour_ = src.hour();
            date_v2_value_.minute_ = src.minute();
            date_v2_value_.second_ = src.second();
            date_v2_value_.microsecond_ = src.microsecond();
        }
    }

    // Construct Date/Datetime type value from string.
    // At least the following formats are recognised (based on number of digits)
    // 'YYMMDD', 'YYYYMMDD', 'YYMMDDHHMMSS', 'YYYYMMDDHHMMSS'
    // 'YY-MM-DD', 'YYYY-MM-DD', 'YY-MM-DD HH.MM.SS'
    // 'YYYYMMDDTHHMMSS'
    bool from_date_str(const char* str, size_t len, int scale = -1, bool convert_zero = false);
    bool from_date_str(const char* str, size_t len, const cctz::time_zone& local_time_zone,
                       int scale = -1, bool convert_zero = false);

    // Convert this value to string
    // this will check type to decide which format to convert
    // TIME:  format 'hh:mm:ss.xxxxxx'
    // DATE:  format 'YYYY-MM-DD'
    // DATETIME:  format 'YYYY-MM-DD hh:mm:ss.xxxxxx'
    int32_t to_buffer(char* buffer, int scale = -1) const;

    char* to_string(char* to, int scale = -1) const;

    // Return true if range or date is invalid
    static bool is_invalid(uint32_t year, uint32_t month, uint32_t day, uint8_t hour,
                           uint8_t minute, uint8_t second, uint32_t microsecond,
                           bool only_time_part = false) {
        if constexpr (is_datetime) {
            if (hour >= 24 || minute >= 60 || second >= 60 || microsecond > 999999) {
                return true;
            }
            if (only_time_part) {
                return false;
            }
        }
        return year > MAX_YEAR || !day || !month || month > 12 ||
               (day > 28 && ((month != 2 && day > S_DAYS_IN_MONTH[month]) ||
                             (month == 2 && day > 28 + doris::is_leap(year))));
    }

    [[nodiscard]] bool check_range_and_set_time(uint16_t year, uint8_t month, uint8_t day,
                                                uint8_t hour, uint8_t minute, uint8_t second,
                                                uint32_t microsecond, bool only_time_part = false) {
        if (is_invalid(year, month, day, hour, minute, second, microsecond, only_time_part)) {
            return false;
        }
        if (only_time_part) {
            // not change date part
            unchecked_set_time(hour, minute, second, microsecond);
        } else {
            unchecked_set_time(year, month, day, hour, minute, second, microsecond);
        }
        return true;
    }

    void unchecked_set_time(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute,
                            uint16_t second, uint32_t microsecond = 0);

    void unchecked_set_time(uint8_t hour, uint8_t minute, uint16_t second, uint32_t microsecond);

    // we frequently use this to do arithmetic operation, so use signed int64_t to avoid overflow.
    int64_t daynr() const {
        return calc_daynr(date_v2_value_.year_, date_v2_value_.month_, date_v2_value_.day_);
    }

    uint8_t hour() const {
        if constexpr (is_datetime) {
            return date_v2_value_.hour_;
        } else {
            return 0;
        }
    }

    uint8_t minute() const {
        if constexpr (is_datetime) {
            return date_v2_value_.minute_;
        } else {
            return 0;
        }
    }

    uint8_t second() const {
        if constexpr (is_datetime) {
            return date_v2_value_.second_;
        } else {
            return 0;
        }
    }

    uint32_t microsecond() const {
        if constexpr (is_datetime) {
            return date_v2_value_.microsecond_;
        } else {
            return 0;
        }
    }

    int64_t time_part_to_seconds() const {
        return hour() * SECOND_PER_HOUR + minute() * SECOND_PER_MINUTE + second();
    }

    void reset_time_part() {
        if constexpr (is_datetime) {
            date_v2_value_.hour_ = 0;
            date_v2_value_.minute_ = 0;
            date_v2_value_.second_ = 0;
            date_v2_value_.microsecond_ = 0;
        }
    }

    int64_t time_part_to_microsecond() const {
        return time_part_to_seconds() * 1000 * 1000 + microsecond();
    }

    uint16_t year() const { return date_v2_value_.year_; }
    uint16_t year_of_week() const;
    uint8_t month() const { return date_v2_value_.month_; }
    int quarter() const { return (date_v2_value_.month_ - 1) / 3 + 1; }
    int week() const { return week(mysql_week_mode(0)); } //00-53
    uint8_t day() const { return date_v2_value_.day_; }

    // Weekday, from 0(Mon) to 6(Sun)
    uint8_t weekday() const { return calc_weekday(daynr(), false); }
    auto day_of_week() const { return (weekday() + 1) % 7 + 1; }

    // The bits in week_format has the following meaning:
    // WEEK_MONDAY_FIRST (0)
    //  If not set:
    //      Sunday is first day of week
    //  If set:
    //      Monday is first day of week
    //
    // WEEK_YEAR (1)
    //  If not set:
    //      Week is in range 0-53
    //      Week 0 is returned for the last week of the previous year (for
    //      a date at start of january) In this case one can get 53 for the
    //      first week of next year.  This flag ensures that the week is
    //      relevant for the given year. Note that this flag is only
    //      relevant if WEEK_JANUARY is not set.
    //  If set:
    //      Week is in range 1-53.
    //      In this case one may get week 53 for a date in January (when
    //      the week is that last week of previous year) and week 1 for a
    //      date in December.
    //
    // WEEK_FIRST_WEEKDAY (2)
    //  If not set
    //      Weeks are numbered according to ISO 8601:1988
    //  If set
    //      The week that contains the first 'first-day-of-week' is week 1.
    //
    // ISO 8601:1988 means that
    //      if the week containing January 1 has
    //      four or more days in the new year, then it is week 1;
    //      Otherwise it is the last week of the previous year, and the
    //      next week is week 1.
    uint8_t week(uint8_t) const;

    uint32_t year_week(uint8_t mode) const;

    // Add interval
    template <TimeUnit unit, typename TO>
    bool date_add_interval(const TimeInterval& interval, DateV2Value<TO>& to_value);

    template <TimeUnit unit, bool need_check = true>
    bool date_add_interval(const TimeInterval& interval);

    template <TimeUnit unit>
    bool date_set_interval(const TimeInterval& interval);

    template <TimeUnit unit>
    bool datetime_trunc(); //datetime trunc, like trunc minute = 0

    //unix_timestamp is called with a timezone argument,
    //it returns seconds of the value of date literal since '1970-01-01 00:00:00' UTC
    bool unix_timestamp(int64_t* timestamp, const std::string& timezone) const;
    bool unix_timestamp(int64_t* timestamp, const cctz::time_zone& ctz) const;
    //the first arg is result of fixed point
    bool unix_timestamp(std::pair<int64_t, int64_t>* timestamp, const std::string& timezone) const;
    bool unix_timestamp(std::pair<int64_t, int64_t>* timestamp, const cctz::time_zone& ctz) const;

    //construct datetime_value from timestamp and timezone
    //timestamp is an internal timestamp value representing seconds since '1970-01-01 00:00:00' UTC. negative avaliable.
    //we don't do any check in it because it's hot path. any usage want ensure the time legality should check itself.
    bool from_unixtime(int64_t, const std::string& timezone);
    void from_unixtime(int64_t, const cctz::time_zone& ctz);
    bool from_unixtime(std::pair<int64_t, int64_t>, const std::string& timezone);
    void from_unixtime(std::pair<int64_t, int64_t>, const cctz::time_zone& ctz);
    bool from_unixtime(int64_t, int32_t, const std::string& timezone, int scale);
    void from_unixtime(int64_t, int32_t, const cctz::time_zone& ctz, int scale);

    bool operator==(const DateV2Value<T>& other) const {
        // NOTE: This is not same with MySQL.
        // MySQL convert both to int with left value type and then compare
        // We think all fields equals.
        return this->to_date_int_val() == other.to_date_int_val();
    }

    bool operator==(const VecDateTimeValue& other) const {
        int64_t ts1 = 0;
        int64_t ts2 = 0;
        this->unix_timestamp(&ts1, TimezoneUtils::default_time_zone);
        other.unix_timestamp(&ts2, TimezoneUtils::default_time_zone);
        return ts1 == ts2;
    }

    bool operator!=(const DateV2Value<T>& other) const {
        return this->to_date_int_val() != other.to_date_int_val();
    }

    bool operator!=(const VecDateTimeValue& other) const { return !(*this == other); }

    bool operator<=(const DateV2Value<T>& other) const { return !(*this > other); }

    bool operator<=(const VecDateTimeValue& other) const {
        int64_t ts1 = 0;
        int64_t ts2 = 0;
        this->unix_timestamp(&ts1, TimezoneUtils::default_time_zone);
        other.unix_timestamp(&ts2, TimezoneUtils::default_time_zone);
        return ts1 <= ts2;
    }

    bool operator>=(const DateV2Value<T>& other) const { return !(*this < other); }

    bool operator>=(const VecDateTimeValue& other) const {
        int64_t ts1 = 0;
        int64_t ts2 = 0;
        this->unix_timestamp(&ts1, TimezoneUtils::default_time_zone);
        other.unix_timestamp(&ts2, TimezoneUtils::default_time_zone);
        return ts1 >= ts2;
    }

    bool operator<(const DateV2Value<T>& other) const {
        return this->to_date_int_val() < other.to_date_int_val();
    }

    bool operator<(const VecDateTimeValue& other) const {
        int64_t ts1 = 0;
        int64_t ts2 = 0;
        this->unix_timestamp(&ts1, TimezoneUtils::default_time_zone);
        other.unix_timestamp(&ts2, TimezoneUtils::default_time_zone);
        return ts1 < ts2;
    }

    bool operator>(const DateV2Value<T>& other) const {
        return this->to_date_int_val() > other.to_date_int_val();
    }

    bool operator>(const VecDateTimeValue& other) const {
        int64_t ts1 = 0;
        int64_t ts2 = 0;
        this->unix_timestamp(&ts1, TimezoneUtils::default_time_zone);
        other.unix_timestamp(&ts2, TimezoneUtils::default_time_zone);
        return ts1 > ts2;
    }

    DateV2Value<T>& operator=(const DateV2Value<T>& other) = default;

    const char* month_name() const;

    const char* day_name() const;

    DateV2Value<T>& operator+=(int64_t count) {
        bool is_neg = false;
        if (count < 0) {
            is_neg = true;
            count = -count;
        }
        if constexpr (is_datetime) {
            TimeInterval interval(SECOND, count, is_neg);
            date_add_interval<SECOND>(interval);
        } else {
            TimeInterval interval(DAY, count, is_neg);
            date_add_interval<DAY>(interval);
        }
        return *this;
    }

    DateV2Value<T>& operator-=(int64_t count) { return *this += -count; }

    DateV2Value<T>& operator++() { return *this += 1; }

    DateV2Value<T>& operator--() { return *this += -1; }

    uint32_t hash(int seed) const { return HashUtil::hash(this, sizeof(*this), seed); }

    int day_of_year() const { return daynr() - calc_daynr(this->year(), 1, 1) + 1; }

    std::string debug_string() const {
        char buf[64];
        char* end = to_string(buf);
        return {buf, static_cast<size_t>(end - buf)};
    }

    bool is_valid_date() const {
        if constexpr (is_datetime) {
            return !is_invalid(this->year(), this->month(), this->day(), this->hour(),
                               this->minute(), this->second(), this->microsecond());
        } else {
            return !is_invalid(this->year(), this->month(), this->day(), 0, 0, 0, 0);
        }
    }

    //only calculate the diff of dd:mm:ss
    template <typename RHS>
    int64_t time_part_diff_without_ms(const RHS& rhs) const {
        return time_part_to_seconds() - rhs.time_part_to_seconds();
    }

    //only calculate the diff of dd:mm:ss.SSSSSS
    template <typename RHS>
    int64_t time_part_diff_in_ms(const RHS& rhs) const {
        return time_part_to_microsecond() - rhs.time_part_to_microsecond();
    }

    template <typename RHS>
    int64_t datetime_diff_in_seconds(const RHS& rhs) const {
        return (daynr() - rhs.daynr()) * SECOND_PER_HOUR * HOUR_PER_DAY +
               time_part_diff_without_ms(rhs);
    }

    template <typename RHS>
    int32_t date_diff_in_days(const RHS& rhs) const {
        return daynr() - rhs.daynr(); // arithmetic calculation will auto promote to signed int32
    }

    int32_t date_diff_in_days_round_to_zero_by_time(const auto& rhs) const {
        int32_t day = this->date_diff_in_days(rhs);
        int64_t ms_diff = this->time_part_diff_in_ms(rhs);
        if (day > 0 && ms_diff < 0) {
            day--;
        } else if (day < 0 && ms_diff > 0) {
            day++;
        }
        return day;
    }

    // used by INT microseconds_diff(DATETIME enddate, DATETIME startdate)
    // return value is int type, so shouldn't have any limit.
    // when used by TIME TIMEDIFF(DATETIME expr1, DATETIME expr2), it's return time type, should have limited.
    template <typename RHS>
    int64_t datetime_diff_in_microseconds(const RHS& rhs) const {
        int64_t diff_m = (daynr() - rhs.daynr()) * HOUR_PER_DAY * SECOND_PER_HOUR * MS_PER_SECOND +
                         time_part_diff_in_ms(rhs);
        return diff_m;
    }

    int64_t datetime_diff_in_seconds_round_to_zero_by_ms(const auto& rhs) const {
        int64_t second = this->datetime_diff_in_seconds(rhs);
        int32_t ms_diff = this->microsecond() - rhs.microsecond();
        if (second > 0 && ms_diff < 0) {
            second--;
        } else if (second < 0 && ms_diff > 0) {
            second++;
        }
        return second;
    }

    underlying_value to_date_int_val() const { return int_val_; }

    bool from_date_int64(int64_t value);

    bool get_date_from_daynr(uint64_t);

    // do check no matter `this` is date or datetime
    template <TimeUnit unit>
    [[nodiscard]] bool test_time_unit(uint32_t val) {
        // is uint so need check upper bound only
        if constexpr (unit == TimeUnit::YEAR) {
            if (val > MAX_YEAR) [[unlikely]] {
                return false;
            }
        } else if constexpr (unit == TimeUnit::MONTH) {
            if (val > MAX_MONTH || !val) [[unlikely]] {
                return false;
            }
        } else if constexpr (unit == TimeUnit::DAY) {
            DCHECK(date_v2_value_.month_ <= MAX_MONTH);
            DCHECK(date_v2_value_.month_ != 0);
            if (!val ||
                (val > S_DAYS_IN_MONTH[date_v2_value_.month_] &&
                 !(is_leap(date_v2_value_.year_) && date_v2_value_.month_ == 2 && val == 29))) {
                return false;
            }
        } else if constexpr (unit == TimeUnit::HOUR) {
            if (val > MAX_HOUR) [[unlikely]] {
                return false;
            }
        } else if constexpr (unit == TimeUnit::MINUTE) {
            if (val > MAX_MINUTE) [[unlikely]] {
                return false;
            }
        } else if constexpr (unit == TimeUnit::SECOND) {
            if (val > MAX_SECOND) [[unlikely]] {
                return false;
            }
        } else if constexpr (unit == TimeUnit::MICROSECOND) {
            if (val > MAX_MICROSECOND) [[unlikely]] {
                return false;
            }
        }
        return true;
    }

    template <TimeUnit unit>
    [[nodiscard]] bool set_time_unit(uint32_t val) {
        // is uint so need check upper bound only
        if constexpr (unit == TimeUnit::YEAR) {
            if (val > MAX_YEAR) [[unlikely]] {
                return false;
            }
            date_v2_value_.year_ = val;
        } else if constexpr (unit == TimeUnit::MONTH) {
            DCHECK(date_v2_value_.year_ <= MAX_YEAR);
            if (val > MAX_MONTH || val == 0) [[unlikely]] {
                return false;
            }
            date_v2_value_.month_ = val;
        } else if constexpr (unit == TimeUnit::DAY) {
            DCHECK(date_v2_value_.year_ <= MAX_YEAR);
            DCHECK(date_v2_value_.month_ <= MAX_MONTH);
            DCHECK(date_v2_value_.month_ != 0);
            if (val == 0) [[unlikely]] {
                return false;
            }
            if (val > S_DAYS_IN_MONTH[date_v2_value_.month_] &&
                !(is_leap(date_v2_value_.year_) && date_v2_value_.month_ == 2 && val == 29)) {
                return false;
            }
            date_v2_value_.day_ = val;
        } else if constexpr (unit == TimeUnit::HOUR) {
            if constexpr (is_datetime) {
                if (val > MAX_HOUR) [[unlikely]] {
                    return false;
                }
                date_v2_value_.hour_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::MINUTE) {
            if constexpr (is_datetime) {
                if (val > MAX_MINUTE) [[unlikely]] {
                    return false;
                }
                date_v2_value_.minute_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::SECOND) {
            if constexpr (is_datetime) {
                if (val > MAX_SECOND) [[unlikely]] {
                    return false;
                }
                date_v2_value_.second_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::MICROSECOND) {
            if constexpr (is_datetime) {
                if (val > MAX_MICROSECOND) [[unlikely]] {
                    return false;
                }
                date_v2_value_.microsecond_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        }
        return true;
    }

    template <TimeUnit unit>
    void unchecked_set_time_unit(uint32_t val) {
        // is uint so need check upper bound only
        if constexpr (unit == TimeUnit::YEAR) {
            date_v2_value_.year_ = val;
        } else if constexpr (unit == TimeUnit::MONTH) {
            date_v2_value_.month_ = val;
        } else if constexpr (unit == TimeUnit::DAY) {
            date_v2_value_.day_ = val;
        } else if constexpr (unit == TimeUnit::HOUR) {
            if constexpr (is_datetime) {
                date_v2_value_.hour_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::MINUTE) {
            if constexpr (is_datetime) {
                date_v2_value_.minute_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::SECOND) {
            if constexpr (is_datetime) {
                date_v2_value_.second_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        } else if constexpr (unit == TimeUnit::MICROSECOND) {
            if constexpr (is_datetime) {
                date_v2_value_.microsecond_ = val;
            } else {
                DCHECK(false) << "shouldn't set for date";
            }
        }
    }

    int64_t to_int64() const {
        if constexpr (is_datetime) {
            return (date_v2_value_.year_ * 10000L + date_v2_value_.month_ * 100 +
                    date_v2_value_.day_) *
                           1000000L +
                   date_v2_value_.hour_ * 10000 + date_v2_value_.minute_ * 100 +
                   date_v2_value_.second_;
        } else {
            return date_v2_value_.year_ * 10000 + date_v2_value_.month_ * 100 + date_v2_value_.day_;
        }
    }

    bool from_date_format_str(const char* format, int format_len, const char* value,
                              int64_t value_len, const char** sub_val_end);
    static constexpr int MAX_DATE_PARTS = 7;
    static constexpr uint32_t MAX_TIME_PART_VALUE[3] = {23, 59, 59};

    void format_datetime(uint32_t* date_v, bool* carry_bits) const;

    void set_int_val(uint64_t val) { this->int_val_ = val; }

private:
    static uint8_t calc_week(const uint32_t& day_nr, const uint16_t& year, const uint8_t& month,
                             const uint8_t& day, uint8_t mode, uint16_t* to_year,
                             bool disable_lut = false);

    bool from_date_str_base(const char* date_str, size_t len, int scale,
                            const cctz::time_zone* local_time_zone, bool convert_zero);

    // Used to construct from int value
    int64_t standardize_timevalue(int64_t value);

    // Helper to set max, min, zero
    void set_zero();

    union {
        T date_v2_value_;
        underlying_value int_val_;
    };

    DateV2Value(uint16_t year, uint8_t month, uint8_t day, uint8_t hour, uint8_t minute,
                uint8_t second, uint32_t microsecond)
            : date_v2_value_(year, month, day, hour, minute, second, microsecond) {}
};

template <typename T>
inline const DateV2Value<T> DateV2Value<T>::FIRST_DAY = DateV2Value<T>(0001, 1, 1, 0, 0, 0, 0);

// only support DATE - DATE (no support DATETIME - DATETIME)
std::size_t operator-(const VecDateTimeValue& v1, const VecDateTimeValue& v2);

template <typename T>
std::size_t operator-(const VecDateTimeValue& v1, const DateV2Value<T>& v2);

template <typename T>
std::size_t operator-(const DateV2Value<T>& v1, const VecDateTimeValue& v2);

std::ostream& operator<<(std::ostream& os, const VecDateTimeValue& value);

std::size_t hash_value(VecDateTimeValue const& value);

template <typename T0, typename T1>
std::size_t operator-(const DateV2Value<T0>& v1, const DateV2Value<T1>& v2);

template <typename T>
std::ostream& operator<<(std::ostream& os, const DateV2Value<T>& value);

template <typename T>
std::size_t hash_value(DateV2Value<T> const& value);

template <TimeUnit unit>
int64_t datetime_diff(const VecDateTimeValue& ts_value1, const VecDateTimeValue& ts_value2) {
    if constexpr (unit == YEAR) {
        int year = (ts_value2.year() - ts_value1.year());
        if (year > 0) {
            year -= (ts_value2.to_datetime_int64() % 10000000000 -
                     ts_value1.to_datetime_int64() % 10000000000) < 0;
        } else if (year < 0) {
            year += (ts_value2.to_datetime_int64() % 10000000000 -
                     ts_value1.to_datetime_int64() % 10000000000) > 0;
        }
        return year;
    } else if constexpr (unit == MONTH) {
        int month = (ts_value2.year() - ts_value1.year()) * 12 +
                    (ts_value2.month() - ts_value1.month());
        if (month > 0) {
            month -= (ts_value2.to_datetime_int64() % 100000000 -
                      ts_value1.to_datetime_int64() % 100000000) < 0;
        } else if (month < 0) {
            month += (ts_value2.to_datetime_int64() % 100000000 -
                      ts_value1.to_datetime_int64() % 100000000) > 0;
        }
        return month;
    } else if constexpr (unit == WEEK) {
        int day = ts_value2.daynr() - ts_value1.daynr();
        if (day > 0) {
            day -= ts_value2.time_part_diff(ts_value1) < 0;
        } else if (day < 0) {
            day += ts_value2.time_part_diff(ts_value1) > 0;
        }
        return day / 7;
    } else if constexpr (unit == DAY) {
        int day = ts_value2.daynr() - ts_value1.daynr();
        if (day > 0) {
            day -= ts_value2.time_part_diff(ts_value1) < 0;
        } else if (day < 0) {
            day += ts_value2.time_part_diff(ts_value1) > 0;
        }
        return day;
    } else if constexpr (unit == HOUR) {
        int64_t second = ts_value2.datetime_diff_in_seconds(ts_value1);
        int64_t hour = second / 60 / 60;
        return hour;
    } else if constexpr (unit == MINUTE) {
        int64_t second = ts_value2.datetime_diff_in_seconds(ts_value1);
        int64_t minute = second / 60;
        return minute;
    } else if constexpr (unit == SECOND) {
        int64_t second = ts_value2.datetime_diff_in_seconds(ts_value1);
        return second;
    } else {
        static_assert(unit == YEAR, "Unsupported TimeUnit for datetime_diff");
    }
}

// ROUND the result TO ZERO( not FLOOR). for datetime_diff<year>, everything less than year is the remainder.
// "ROUND TO ZERO" means `years_diff('2020-05-05', '2015-06-06')` gets 4 and
//  `years_diff('2015-06-06', '2020-05-05')` gets -4.
template <TimeUnit UNIT, typename T0, typename T1>
int64_t datetime_diff(const DateV2Value<T0>& ts_value1, const DateV2Value<T1>& ts_value2) {
    constexpr uint64_t uint64_minus_one = -1;
    // for YEAR and MONTH: calculate the diff of year or month, and use bitmask to get the remainder of all other
    // parts. then round to zero by the remainder.
    if constexpr (UNIT == YEAR) {
        int year = (ts_value2.year() - ts_value1.year());
        if constexpr (std::is_same_v<T0, T1>) {
            int year_width =
                    DateV2Value<T0>::is_datetime ? DATETIMEV2_YEAR_WIDTH : DATEV2_YEAR_WIDTH;
            decltype(ts_value2.to_date_int_val()) minus_one = -1;
            if (year > 0) {
                year -= ((ts_value2.to_date_int_val() & (minus_one >> year_width)) <
                         (ts_value1.to_date_int_val() & (minus_one >> year_width)));
            } else if (year < 0) {
                year += ((ts_value2.to_date_int_val() & (minus_one >> year_width)) >
                         (ts_value1.to_date_int_val() & (minus_one >> year_width)));
            }
        } else if constexpr (std::is_same_v<T0, DateV2ValueType>) {
            auto ts1_int_value = ((uint64_t)ts_value1.to_date_int_val()) << TIME_PART_LENGTH;
            if (year > 0) {
                year -= ((ts_value2.to_date_int_val() &
                          (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)) <
                         (ts1_int_value & (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)));
            } else if (year < 0) {
                year += ((ts_value2.to_date_int_val() &
                          (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)) >
                         (ts1_int_value & (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)));
            }
        } else {
            auto ts2_int_value = ((uint64_t)ts_value2.to_date_int_val()) << TIME_PART_LENGTH;
            if (year > 0) {
                year -= ((ts2_int_value & (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)) <
                         (ts_value1.to_date_int_val() &
                          (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)));
            } else if (year < 0) {
                year += ((ts2_int_value & (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)) >
                         (ts_value1.to_date_int_val() &
                          (uint64_minus_one >> DATETIMEV2_YEAR_WIDTH)));
            }
        }

        return year;
    } else if constexpr (UNIT == MONTH) {
        int month = (ts_value2.year() - ts_value1.year()) * 12 +
                    (ts_value2.month() - ts_value1.month());
        if constexpr (std::is_same_v<T0, T1>) {
            int shift_bits = DateV2Value<T0>::is_datetime
                                     ? DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH
                                     : DATEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH;
            decltype(ts_value2.to_date_int_val()) minus_one = -1;
            if (month > 0) {
                month -= ((ts_value2.to_date_int_val() & (minus_one >> shift_bits)) <
                          (ts_value1.to_date_int_val() & (minus_one >> shift_bits)));
            } else if (month < 0) {
                month += ((ts_value2.to_date_int_val() & (minus_one >> shift_bits)) >
                          (ts_value1.to_date_int_val() & (minus_one >> shift_bits)));
            }
        } else if constexpr (std::is_same_v<T0, DateV2ValueType>) {
            auto ts1_int_value = ((uint64_t)ts_value1.to_date_int_val()) << TIME_PART_LENGTH;
            if (month > 0) {
                month -= ((ts_value2.to_date_int_val() &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))) <
                          (ts1_int_value &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))));
            } else if (month < 0) {
                month += ((ts_value2.to_date_int_val() &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))) >
                          (ts1_int_value &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))));
            }
        } else {
            auto ts2_int_value = ((uint64_t)ts_value2.to_date_int_val()) << TIME_PART_LENGTH;
            if (month > 0) {
                month -= ((ts2_int_value &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))) <
                          (ts_value1.to_date_int_val() &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))));
            } else if (month < 0) {
                month += ((ts2_int_value &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))) >
                          (ts_value1.to_date_int_val() &
                           (uint64_minus_one >> (DATETIMEV2_YEAR_WIDTH + DATETIMEV2_MONTH_WIDTH))));
            }
        }
        return month;
    } else if constexpr (UNIT == WEEK) {
        return ts_value2.date_diff_in_days_round_to_zero_by_time(ts_value1) / 7;
    } else if constexpr (UNIT == DAY) {
        return ts_value2.date_diff_in_days_round_to_zero_by_time(ts_value1);
    } else if constexpr (UNIT == HOUR) {
        return ts_value2.datetime_diff_in_seconds_round_to_zero_by_ms(ts_value1) / 60 / 60;
    } else if constexpr (UNIT == MINUTE) {
        return ts_value2.datetime_diff_in_seconds_round_to_zero_by_ms(ts_value1) / 60;
    } else if constexpr (UNIT == SECOND) {
        return ts_value2.datetime_diff_in_seconds_round_to_zero_by_ms(ts_value1);
    } else if constexpr (UNIT == MILLISECOND) {
        // C++ naturally rounds to zero
        return ts_value2.datetime_diff_in_microseconds(ts_value1) / 1000;
    } else if constexpr (UNIT == MICROSECOND) {
        // no precision loss
        return ts_value2.datetime_diff_in_microseconds(ts_value1);
    } else {
        static_assert(UNIT == YEAR, "Unsupported TimeUnit for datetime_diff");
    }
    // Rethink the default return value
    return 0;
}

/**
 * Date dict table. date range is [1900-01-01, 2039-12-31].
 */
class date_day_offset_dict {
private:
    static constexpr int DAY_BEFORE_EPOCH = 25567;                           // 1900-01-01
    static constexpr int DAY_AFTER_EPOCH = 25566;                            // 2039-12-31
    static constexpr int DICT_DAYS = DAY_BEFORE_EPOCH + 1 + DAY_AFTER_EPOCH; // 1 means 1970-01-01

    static constexpr int START_YEAR = 1900; // 1900-01-01
    static constexpr int END_YEAR = 2039;   // 2039-10-24
    static constexpr int DAY_OFFSET_CAL_START_POINT_DAYNR =
            719528; // 1970-01-01 (start from 0000-01-01, 0000-01-01 is day 1, returns 1)

    static std::array<DateV2Value<DateV2ValueType>, DICT_DAYS> DATE_DAY_OFFSET_ITEMS;
    static std::array<std::array<std::array<int, 31>, 12>, 140> DATE_DAY_OFFSET_DICT;

    static bool DATE_DAY_OFFSET_ITEMS_INIT;

    static date_day_offset_dict instance;

    date_day_offset_dict();
    ~date_day_offset_dict() = default;
    date_day_offset_dict(const date_day_offset_dict&) = default;
    date_day_offset_dict& operator=(const date_day_offset_dict&) = default;

public:
    static bool can_speed_up_calc_daynr(int year) { return year >= START_YEAR && year <= END_YEAR; }

    static int get_offset_by_daynr(int daynr) { return daynr - DAY_OFFSET_CAL_START_POINT_DAYNR; }

    static bool can_speed_up_daynr_to_date(int daynr) {
        auto res = get_offset_by_daynr(daynr);
        return res >= 0 ? res <= DAY_AFTER_EPOCH : -res <= DAY_BEFORE_EPOCH;
    }

    static date_day_offset_dict& get() { return instance; }

    static bool get_dict_init() { return DATE_DAY_OFFSET_ITEMS_INIT; }

    inline DateV2Value<DateV2ValueType> operator[](int day) const {
        int index = day + DAY_BEFORE_EPOCH;
        if (LIKELY(index >= 0 && index < DICT_DAYS)) {
            return DATE_DAY_OFFSET_ITEMS[index];
        } else {
            DateV2Value<DateV2ValueType> d = DATE_DAY_OFFSET_ITEMS[0];
            return d += index;
        }
    }

    int daynr(int year, int month, int day) const {
        return DATE_DAY_OFFSET_DICT[year - START_YEAR][month - 1][day - 1];
    }
};

inline uint32_t calc_daynr(uint16_t year, uint8_t month, uint8_t day) {
    // date_day_offet_dict range from [1900-01-01, 2039-12-31]
    if (date_day_offset_dict::can_speed_up_calc_daynr(year) &&
        LIKELY(date_day_offset_dict::get_dict_init())) {
        return date_day_offset_dict::get().daynr(year, month, day);
    }

    uint32_t delsum = 0;
    int y = year;

    if (year == 0 && month == 0) {
        return 0;
    }
    if (year == 0 && month == 1 && day == 1) {
        return 1;
    }

    /* Cast to int to be able to handle month == 0 */
    delsum = 365 * y + 31 * (month - 1) + day;
    if (month <= 2) {
        // No leap year
        y--;
    } else {
        // This is great!!!
        // 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
        // 0, 0, 3, 3, 4, 4, 5, 5, 5,  6,  7,  8
        delsum -= (month * 4 + 23) / 10;
    }
    // Every 400 year has 97 leap year, 100, 200, 300 are not leap year.
    return delsum + y / 4 - y / 100 + y / 400;
}

template <typename T>
struct DateTraits {};

template <>
struct DateTraits<int64_t> {
    using T = VecDateTimeValue;
    using DateType = vectorized::DataTypeDateTime;
};

template <>
struct DateTraits<uint32_t> {
    using T = DateV2Value<DateV2ValueType>;
    using DateType = vectorized::DataTypeDateV2;
};

template <>
struct DateTraits<uint64_t> {
    using T = DateV2Value<DateTimeV2ValueType>;
    using DateType = vectorized::DataTypeDateTimeV2;
};
#include "common/compile_check_avoid_end.h"
} // namespace doris

template <>
struct std::hash<doris::VecDateTimeValue> {
    size_t operator()(const doris::VecDateTimeValue& v) const { return doris::hash_value(v); }
};

template <>
struct std::hash<doris::DateV2Value<doris::DateV2ValueType>> {
    size_t operator()(const doris::DateV2Value<doris::DateV2ValueType>& v) const {
        return doris::hash_value(v);
    }
};

template <>
struct std::hash<doris::DateV2Value<doris::DateTimeV2ValueType>> {
    size_t operator()(const doris::DateV2Value<doris::DateTimeV2ValueType>& v) const {
        return doris::hash_value(v);
    }
};

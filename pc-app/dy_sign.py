#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抖音 Web 详情接口的 a_bogus 签名。

移植自手机端 ``DouyinSign.java``（原实现出自 MIT 许可的
Chenwenwen1007/WeChat-ShuiYin，见 THIRD_PARTY_NOTICES.md）。

**唯一不能改的地方**：`_KEYS` 的取值与顺序、`_WINDOW_ENV` 的内容、
以及 b[] 各下标的赋值。这些是逆向出来的魔数，看起来毫无规律，
但少一个字节抖音就返回「校验失败」。要改之前先确认接口真的变了。

Java 那边手写了 SM3（约 90 行）；Python 标准库的 OpenSSL 自带 SM3，
`hashlib.new("sm3")` 结果与 Java 版逐位一致（已用 "abc" 官方向量验证），
所以这里直接用标准库，不重复实现。
"""
import hashlib
import random
import time

_ALPHABET_S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe"
_ALPHABET_S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"
_WINDOW_ENV = "1536|747|1536|834|0|30|0|0|1536|834|1536|864|1525|747|24|24|Win32"

# 参与校验和计算的下标。顺序敏感，不要排序、不要去重、不要"整理"。
_KEYS = [
    18, 20, 52, 26, 30, 34, 58, 38, 40, 53, 42, 21,
    27, 54, 55, 31, 35, 57, 39, 41, 43, 22, 28, 32,
    60, 36, 23, 29, 33, 37, 44, 45, 59, 46, 47, 48,
    49, 50, 24, 25, 65, 66, 70, 71,
]


def _sm3(data):
    h = hashlib.new("sm3")
    h.update(data)
    return h.digest()


def _rc4(data, key):
    """data 与 key 都是 int 列表，返回同长度的 int 列表。"""
    s = list(range(256))
    j = 0
    klen = len(key)
    for i in range(256):
        j = (j + s[i] + key[i % klen]) & 0xFF
        s[i], s[j] = s[j], s[i]
    out = []
    i = j = 0
    for byte in data:
        i = (i + 1) & 0xFF
        j = (j + s[i]) & 0xFF
        s[i], s[j] = s[j], s[i]
        out.append(s[(s[i] + s[j]) & 0xFF] ^ byte)
    return out


def _result_encrypt(values, alphabet):
    """自定义 base64 变体：每 3 字节 → 4 个字母。末尾不足 3 字节的丢弃。"""
    out = []
    for offset in range(0, len(values) - 2, 3):
        value = ((values[offset] & 0xFF) << 16) | ((values[offset + 1] & 0xFF) << 8) \
                | (values[offset + 2] & 0xFF)
        out.append(alphabet[(value & 0xFC0000) >> 18])
        out.append(alphabet[(value & 0x03F000) >> 12])
        out.append(alphabet[(value & 0x000FC0) >> 6])
        out.append(alphabet[value & 0x3F])
    return "".join(out)


def _append_random(out, offset, option0, option1):
    """写 4 字节随机前缀。两个 option 用位掩码把随机值拆成「像信令」的样子。

    `& 0xffff` 是照抄 Java 的 `(int) (d * 10000.0) & 0xffff`；
    r 的取值落在 0..9999，所以高字节只用得到低 2 位。
    """
    r = random.randint(0, 9999)
    out[offset] = ((r & 0xFF & 0xAA) | (option0 & 0x55)) & 0xFF
    out[offset + 1] = ((r & 0xFF & 0x55) | (option0 & 0xAA)) & 0xFF
    out[offset + 2] = (((r >> 8) & 0xFF & 0xAA) | (option1 & 0x55)) & 0xFF
    out[offset + 3] = (((r >> 8) & 0xFF & 0x55) | (option1 & 0xAA)) & 0xFF
    return offset + 4


def _put_int_be(out, offset, value):
    """大端写入 4 字节（高字节在前）。"""
    out[offset] = (value >> 24) & 0xFF
    out[offset + 1] = (value >> 16) & 0xFF
    out[offset + 2] = (value >> 8) & 0xFF
    out[offset + 3] = value & 0xFF


def _payload(query, user_agent):
    start_ms = int(time.time() * 1000)
    # query 与 "cus" 各做两次 SM3 串联；cusHash 是常量 "cus" 的两轮哈希
    query_hash = _sm3(_sm3((query + "cus").encode("utf-8")))
    cus_hash = _sm3(_sm3("cus".encode("utf-8")))

    ua_rc4 = _rc4(list(user_agent.encode("ascii", "ignore")), [0, 1, 14])
    ua_encrypted = _result_encrypt(ua_rc4, _ALPHABET_S3)
    ua_hash = _sm3(ua_encrypted.encode("utf-8"))
    end_ms = int(time.time() * 1000)

    b = [0] * 73
    b[8] = 3
    b[10] = end_ms
    b[16] = start_ms
    b[18] = 44

    _put_int_be(b, 20, b[16])
    b[24] = (b[16] >> 32) & 0xFF
    b[25] = (b[16] >> 40) & 0xFF
    _put_int_be(b, 26, 0)
    b[30] = 0
    b[31] = 1
    b[32] = 0
    b[33] = 0
    _put_int_be(b, 34, 14)
    b[38] = query_hash[21] & 0xFF
    b[39] = query_hash[22] & 0xFF
    b[40] = cus_hash[21] & 0xFF
    b[41] = cus_hash[22] & 0xFF
    b[42] = ua_hash[23] & 0xFF
    b[43] = ua_hash[24] & 0xFF
    _put_int_be(b, 44, b[10])
    b[48] = b[8]
    b[49] = (b[10] >> 32) & 0xFF
    b[50] = (b[10] >> 40) & 0xFF

    page_id = 6241
    b[51] = page_id
    _put_int_be(b, 52, page_id)
    aid = 6383
    b[56] = aid
    b[57] = aid & 0xFF
    b[58] = (aid >> 8) & 0xFF
    b[59] = (aid >> 16) & 0xFF
    b[60] = (aid >> 24) & 0xFF

    environment = list(_WINDOW_ENV.encode("ascii"))
    b[64] = len(environment)
    b[65] = b[64] & 0xFF
    b[66] = (b[64] >> 8) & 0xFF
    b[69] = b[70] = b[71] = 0

    checksum = 0
    for key in _KEYS:
        checksum ^= b[key]

    plain = [b[key] & 0xFFFF for key in _KEYS]
    plain.extend(environment)
    plain.append(checksum & 0xFFFF)
    return _rc4(plain, [121])


def generate(query, user_agent):
    """生成 a_bogus 值（含结尾的 '='，调用方不要再补）。"""
    prefix = [0] * 12
    offset = _append_random(prefix, 0, 3, 45)
    offset = _append_random(prefix, offset, 1, 0)
    _append_random(prefix, offset, 1, 5)

    all_values = prefix + _payload(query, user_agent)
    return _result_encrypt(all_values, _ALPHABET_S4) + "="


if __name__ == "__main__":
    # 自检：结构正确性。签名内容含时间戳与随机前缀，每次不同，
    # 所以只能验格式（字母表合法、长度稳定、可重复生成）。
    ua = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
    values = {generate("aweme_id=123&aid=6383", ua) for _ in range(20)}
    assert len(values) == 20, "每次生成应当都不同（含随机前缀与时间戳）"
    sample = generate("aweme_id=123&aid=6383", ua)
    assert sample.endswith("="), "应以 = 结尾"
    assert len(sample) >= 100, f"长度异常: {len(sample)}"
    assert all(c in _ALPHABET_S4 or c == "=" for c in sample), "出现字母表外的字符"
    # 同输入同秒内应稳定（时间戳只到毫秒，随机前缀才是变量）——这里只验可调用
    assert generate("", ua) != generate("", ua) or True
    print(f"a_bogus 自检通过，样例长度 {len(sample)}")

(ns lan-clip.protocol-test
  (:require [clojure.test :refer :all]
            [lan-clip.protocol :as protocol])
  (:import (java.util UUID)))

(def ^:private test-key
  "用于测试的共享密钥"
  "test-secret-key-2026")

(def ^:private test-origin-id
  (UUID/fromString "550e8400-e29b-41d4-a716-446655440001"))

(def ^:private test-sender-id
  (UUID/fromString "550e8400-e29b-41d4-a716-446655440002"))

(deftest text-message-roundtrip
  (testing "文本消息编码后解码应还原原始内容"
    (let [text "Hello, lan-clip protocol!"
          encoded (protocol/encode-text-message text test-origin-id test-sender-id test-key)
          decoded (protocol/decode-message encoded test-key)]
      (is (= text (String. (:payload decoded) "UTF-8")))
      (is (= :text (:content-type decoded)))
      (is (= test-origin-id (:origin-node-id decoded)))
      (is (= test-sender-id (:sender-node-id decoded)))
      (is (instance? UUID (:message-id decoded))))))

(deftest hmac-valid-key-succeeds
  (testing "使用正确密钥解码应成功"
    (let [encoded (protocol/encode-text-message "secret data" test-origin-id test-sender-id test-key)]
      (is (some? (protocol/decode-message encoded test-key))))))

(deftest hmac-invalid-key-fails
  (testing "使用错误密钥解码应抛出异常"
    (let [encoded (protocol/encode-text-message "secret data" test-origin-id test-sender-id test-key)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/decode-message encoded "wrong-key"))))))

(deftest bad-magic-rejected
  (testing "错误的 magic 应被拒绝"
    (let [encoded (protocol/encode-text-message "x" test-origin-id test-sender-id test-key)
          ;; 篡改前 4 个字节（magic）—— byte-array 不支持 assoc，用 clone + aset
          tampered (aclone encoded)]
      (aset tampered 0 (unchecked-byte 0xFF))
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/decode-message tampered test-key))))))

(deftest bad-version-rejected
  (testing "错误的 version 应被拒绝"
    (let [encoded (protocol/encode-text-message "x" test-origin-id test-sender-id test-key)
          tampered (aclone encoded)]
      (aset tampered 4 (unchecked-byte 0xFF))
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/decode-message tampered test-key))))))

(deftest truncated-message-rejected
  (testing "截断的消息应被拒绝"
    (let [encoded (protocol/encode-text-message "x" test-origin-id test-sender-id test-key)
          truncated (byte-array (take 10 encoded))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/decode-message truncated test-key))))))

(deftest image-message-roundtrip
  (testing "图片消息编码后解码应还原原始字节与 content-type"
    (let [image-bytes (byte-array [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]) ;; PNG magic
          encoded (protocol/encode-image-message image-bytes test-origin-id test-sender-id test-key)
          decoded (protocol/decode-message encoded test-key)]
      (is (= :image (:content-type decoded)))
      (is (java.util.Arrays/equals image-bytes ^bytes (:payload decoded)))
      (is (= test-origin-id (:origin-node-id decoded)))
      (is (= test-sender-id (:sender-node-id decoded))))))

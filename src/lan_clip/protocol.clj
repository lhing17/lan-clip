(ns lan-clip.protocol
  "lan-clip 二进制协议：替代 Java 对象序列化，提供显式消息格式与 HMAC-SHA256 认证。

  消息格式（大端序）：
    [4 bytes]  magic (0x4C434C50 = \"LCLP\")
    [1 byte]   version
    [16 bytes] message-id (UUID)
    [16 bytes] origin-node-id (UUID)
    [16 bytes] sender-node-id (UUID)
    [1 byte]   content-type (1=text 2=image 3=file-list)
    [4 bytes]  metadata-length
    [4 bytes]  payload-length
    [N bytes]  metadata (UTF-8)
    [M bytes]  payload
    [32 bytes] HMAC-SHA256"
  (:import (java.nio ByteBuffer)
           (java.security MessageDigest)
           (java.util Arrays UUID)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:const MAGIC 0x4C434C50)
(def ^:const VERSION 1)
(def ^:const HMAC-SIZE 32)
(def ^:const HEADER-SIZE (+ 4 1 16 16 16 1 4 4)) ;; 62
(def ^:const MIN-MESSAGE-SIZE (+ HEADER-SIZE HMAC-SIZE)) ;; 94

(defrecord Message [message-id origin-node-id sender-node-id content-type metadata payload])

(defn- content-type->byte [ct]
  (case ct
    :text (byte 1)
    :image (byte 2)
    :file-list (byte 3)
    (byte 0)))

(defn- byte->content-type [b]
  (case (Byte/toUnsignedInt b)
    1 :text
    2 :image
    3 :file-list
    :unknown))

(defn- uuid->bytes [^UUID uuid]
  (let [buf (ByteBuffer/allocate 16)]
    (.putLong buf (.getMostSignificantBits uuid))
    (.putLong buf (.getLeastSignificantBits uuid))
    (.array buf)))

(defn- bytes->uuid [^bytes bs]
  (let [buf (ByteBuffer/wrap bs)]
    (UUID. (.getLong buf) (.getLong buf))))

(defn- hmac-sha256 [^bytes data ^String secret-key]
  (let [mac (Mac/getInstance "HmacSHA256")
        key (SecretKeySpec. (.getBytes secret-key "UTF-8") "HmacSHA256")]
    (.init mac key)
    (.doFinal mac data)))

(defn- encode-message
  "通用消息编码：content-type + payload-bytes → 带 HMAC 的二进制字节数组。"
  [content-type ^bytes payload-bytes origin-node-id sender-node-id secret-key]
  (let [message-id (UUID/randomUUID)
        metadata-bytes (.getBytes (pr-str {:content-type content-type}) "UTF-8")
        metadata-len (count metadata-bytes)
        payload-len (count payload-bytes)
        body-size (+ metadata-len payload-len)
        total-size (+ HEADER-SIZE body-size HMAC-SIZE)
        buf (ByteBuffer/allocate total-size)]
    (.putInt buf MAGIC)
    (.put buf (byte VERSION))
    (.put buf (uuid->bytes message-id))
    (.put buf (uuid->bytes origin-node-id))
    (.put buf (uuid->bytes sender-node-id))
    (.put buf (content-type->byte content-type))
    (.putInt buf metadata-len)
    (.putInt buf payload-len)
    (.put buf metadata-bytes)
    (.put buf payload-bytes)
    (let [data (Arrays/copyOfRange (.array buf) 0 (+ HEADER-SIZE body-size))
          sig (hmac-sha256 data secret-key)]
      (.put buf sig))
    (.array buf)))

(defn encode-text-message
  "将文本消息编码为带 HMAC 签名的二进制字节数组。"
  [text origin-node-id sender-node-id secret-key]
  (encode-message :text (.getBytes text "UTF-8") origin-node-id sender-node-id secret-key))

(defn encode-image-message
  "将图片消息（PNG 字节数组）编码为带 HMAC 签名的二进制字节数组。"
  [^bytes image-bytes origin-node-id sender-node-id secret-key]
  (encode-message :image image-bytes origin-node-id sender-node-id secret-key))

(defn encode-file-list-message
  "将文件列表消息（zip 字节数组）编码为带 HMAC 签名的二进制字节数组。"
  [^bytes zip-bytes origin-node-id sender-node-id secret-key]
  (encode-message :file-list zip-bytes origin-node-id sender-node-id secret-key))

(defn decode-message
  "解码二进制消息并验证 HMAC。验证失败或格式错误时抛 ex-info。"
  [^bytes encoded secret-key]
  (when (< (count encoded) MIN-MESSAGE-SIZE)
    (throw (ex-info "Message too short"
                    {:cause :truncated :actual (count encoded) :expected MIN-MESSAGE-SIZE})))
  (let [buf (ByteBuffer/wrap encoded)]
    (let [magic (.getInt buf)]
      (when (not= magic MAGIC)
        (throw (ex-info "Invalid magic"
                        {:cause :bad-magic :actual magic :expected MAGIC}))))
    (let [version (.get buf)]
      (when (not= version (byte VERSION))
        (throw (ex-info "Invalid version"
                        {:cause :bad-version :actual version :expected VERSION}))))
    (let [msg-id-b (byte-array 16)
          _ (.get buf msg-id-b)
          origin-b (byte-array 16)
          _ (.get buf origin-b)
          sender-b (byte-array 16)
          _ (.get buf sender-b)
          ct-b (.get buf)
          meta-len (.getInt buf)
          pay-len (.getInt buf)
          meta-b (byte-array meta-len)
          _ (.get buf meta-b)
          pay-b (byte-array pay-len)
          _ (.get buf pay-b)
          stored (byte-array HMAC-SIZE)
          _ (.get buf stored)
          data-end (+ HEADER-SIZE meta-len pay-len)
          data (Arrays/copyOfRange encoded 0 data-end)
          computed (hmac-sha256 data secret-key)]
      (when-not (MessageDigest/isEqual stored computed)
        (throw (ex-info "HMAC verification failed" {:cause :bad-hmac})))
      (->Message (bytes->uuid msg-id-b)
                 (bytes->uuid origin-b)
                 (bytes->uuid sender-b)
                 (byte->content-type ct-b)
                 (String. meta-b "UTF-8")
                 pay-b))))

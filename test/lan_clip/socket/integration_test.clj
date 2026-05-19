(ns lan-clip.socket.integration-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.protocol :as protocol])
  (:import (io.netty.buffer ByteBuf)
           (io.netty.channel ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.embedded EmbeddedChannel)
           (java.awt.image BufferedImage)
           (java.util Arrays UUID)
           (java.util.concurrent LinkedBlockingQueue)
           (lan_clip.protocol Message)))

(defn- capturing-handler [queue]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [_ msg]
      (.offer queue msg))))

(deftest text-roundtrip-through-client-server-pipelines
  (testing "client encoder 输出应能被 server decoder 正确解码为 Message"
    (let [node-id (UUID/randomUUID)
          secret "integration-test"
          ;; Client pipeline: encoder
          client-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder node-id node-id secret)]))
          _ (.writeOutbound client-ch (into-array Object ["integration text"]))
          ^ByteBuf frame (.readOutbound client-ch)

          ;; Server pipeline: decoder + capturing handler
          received (LinkedBlockingQueue.)
          server-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder secret) (capturing-handler received)]))
          _ (.writeInbound server-ch (into-array Object [frame]))

          msg (.poll received)]
      (try
        (is (instance? Message msg))
        (is (= :text (:content-type msg)))
        (is (= "integration text" (String. ^bytes (:payload msg) "UTF-8")))
        (is (= node-id (:origin-node-id msg)))
        (is (= node-id (:sender-node-id msg)))
        (finally
          (.finish client-ch)
          (.finish server-ch))))))

(deftest image-roundtrip-through-client-server-pipelines
  (testing "client encoder 输出应能被 server decoder 正确解码为 image Message"
    (let [node-id (UUID/randomUUID)
          secret "integration-test"
          ;; 创建 2x2 测试图片
          img (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB)
          ;; Client pipeline: encoder
          client-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-encoder node-id node-id secret)]))
          _ (.writeOutbound client-ch (into-array Object [img]))
          ^ByteBuf frame (.readOutbound client-ch)

          ;; Server pipeline: decoder + capturing handler
          received (LinkedBlockingQueue.)
          server-ch (EmbeddedChannel. (into-array ChannelHandler [(codec/->protocol-decoder secret) (capturing-handler received)]))
          _ (.writeInbound server-ch (into-array Object [frame]))

          msg (.poll received)]
      (try
        (is (instance? Message msg))
        (is (= :image (:content-type msg)))
        (is (> (count (:payload msg)) 0) "payload 应为非空 PNG 字节")
        ;; 验证 PNG magic
        (is (Arrays/equals (byte-array [0x89 0x50 0x4E 0x47]) (Arrays/copyOfRange ^bytes (:payload msg) 0 4)))
        (is (= node-id (:origin-node-id msg)))
        (is (= node-id (:sender-node-id msg)))
        (finally
          (.finish client-ch)
          (.finish server-ch))))))

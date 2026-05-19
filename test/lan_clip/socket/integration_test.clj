(ns lan-clip.socket.integration-test
  (:require [clojure.test :refer :all]
            [lan-clip.socket.protocol-codec :as codec]
            [lan-clip.protocol :as protocol])
  (:import (io.netty.buffer ByteBuf)
           (io.netty.channel ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.embedded EmbeddedChannel)
           (java.util UUID)
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

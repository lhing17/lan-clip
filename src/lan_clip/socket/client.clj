(ns lan-clip.socket.client
  (:gen-class)
  (:require [lan-clip.log :as log]
            [lan-clip.socket.protocol-codec :as codec])
  (:import (io.netty.channel.nio NioEventLoopGroup)
           (io.netty.bootstrap Bootstrap)
           (io.netty.channel ChannelFuture ChannelOption ChannelInitializer ChannelHandler ChannelInboundHandlerAdapter)
           (io.netty.channel.socket.nio NioSocketChannel)
           (io.netty.channel.socket SocketChannel)
           (java.awt Image)
           (java.io File)
           (java.util List)))

(defprotocol RunnableClient
  (run [this]))

(defn content-handler [content]
  (proxy
      [ChannelInboundHandlerAdapter]
      []
      (channelActive [ctx]
        (when (or (string? content)
                  (instance? Image content)
                  (and (instance? List content)
                       (every? #(instance? File %) content)))
          (.writeAndFlush ctx content)))

      (exceptionCaught [ctx cause]
        (log/log! :error (str cause))
        (.close ctx))))

(defn get-handlers [content secret-key node-id]
  (into-array
    ChannelHandler [(codec/->protocol-encoder node-id node-id secret-key)
                    (codec/->protocol-decoder secret-key)
                    (content-handler content)]))

(defn ->chan-init [content secret-key node-id]
  (proxy [ChannelInitializer]
         []
    (initChannel [^SocketChannel ch]
      (-> ch (.pipeline) (.addLast (get-handlers content secret-key node-id))))))

(defrecord Client [host port content secret-key node-id]
  RunnableClient
  (run [this]
    (let [worker-group (NioEventLoopGroup.)
          b (Bootstrap.)]
      (try
        (let [^ChannelFuture f
              (-> b
                  (.group worker-group)
                  (.channel NioSocketChannel)
                  (.option ChannelOption/TCP_NODELAY true)
                  (.handler (->chan-init content secret-key node-id))
                  (.connect host port)
                  (.sync))]
          (-> f (.channel) (.closeFuture) (.sync)))
        (finally
          (.shutdownGracefully worker-group))))))

(defn run-with-retry
  "带重试执行 client/run。连接或发送失败时自动重试，成功立即返回。
  retry-count: 最大尝试次数（含首次），默认 3
  retry-delay-ms: 每次重试间隔毫秒，默认 1000
  最终失败时抛出最后一次捕获的异常。"
  ([client]
   (run-with-retry client 3 1000))
  ([client retry-count retry-delay-ms]
   (loop [attempt 1]
     (let [result (try
                    (run client)
                    ::success
                    (catch InterruptedException e
                      (throw e))
                    (catch Exception e
                      e))]
       (if (= ::success result)
         ::success
         (if (< attempt retry-count)
           (do
             (log/log! :warn (str "发送失败（第" attempt "/" retry-count "次），" retry-delay-ms "ms 后重试"))
             (Thread/sleep retry-delay-ms)
             (recur (inc attempt)))
           (do
             (log/log! :error (str "发送失败，已放弃（共" retry-count "次）：" (.getMessage ^Exception result)))
             (throw result))))))))

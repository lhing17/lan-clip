(ns lan-clip.sidecar
  "lan-clip sidecar 入口。
  提供可被 Tauri 调用的后台服务启动函数，启动成功后输出 READY 标记。"
  (:require [lan-clip.api :as api]))

(defn start!
  "启动 sidecar HTTP API server 并在成功后输出 READY 标记到 stdout。
  返回 server 对象（可调用的停止函数）。"
  [port]
  (let [server (api/start-api-server port)]
    (println "READY")
    server))

(defn -main
  "sidecar 主入口。默认监听端口 9615，可通过第一个命令行参数指定。"
  [& args]
  (let [port (or (when (seq args)
                   (try
                     (Integer/parseInt (first args))
                     (catch NumberFormatException _ nil)))
                 9615)]
    (start! port)
    ;; 阻塞主线程，保持进程存活
    @(promise)))

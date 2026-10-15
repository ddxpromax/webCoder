# webCoder

## 持久化：

* 问题：
    * 前端Stop后，loadRecentMessage（）调用太急，导致数据库messages的status由pending变为cancelled前被读取。且因为前端页面messages变量只增不减处理，下一次loadRecentMessage时被前端排除，无法刷新前端status由pending变为cancelled。
    前端第一次load结果错误，以后也没有修改机会。数据库持久化路径正常。

* 解决：
    * submitExplanation（）在调用loadRecentMessage前先轮询（由loadRecentMessage返回结果实现），通过后再正式调用loadRecentMessage获取messages。
    * 更改只增不减处理，即使loadRecentMessage返回了前端已有的messages，也不能直接排除，而应该覆盖前端已有信息。即使前端暂时出错，也能通过后续持久化读取数据时补救。

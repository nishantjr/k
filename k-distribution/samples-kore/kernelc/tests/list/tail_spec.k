// Copyright (c) 2014-2019 K Team. All Rights Reserved.
module TAIL-SPEC
  imports KERNELC

rule
  <struct>... STRUCT:Map ...</struct>
  <fun>... FUN:Map ...</fun>
  <k>
    tail(tv(struct listNode *, LP:Pointer))
  =>
    tv(struct listNode *, Tail:Pointer)
  ...</k>
  <heap>...
    LP |-> (
      val  |-> tv(int, _:Int)
      next |-> tv(struct listNode *, Tail)
    )
    list(Tail)(L:List)
  ...</heap>
endmodule

// aemu64: entry, raw syscalls and the jump into the guest
	.text
	.globl _start
	.type _start,%function
_start:
	mov x29, #0
	mov x30, #0
	mov x0, sp
	bl aemu_main
	brk #1

// long sc(long nr, long a, long b, long c, long d, long e, long f)
	.globl sc
	.type sc,%function
sc:
	mov x8, x0
	mov x0, x1
	mov x1, x2
	mov x2, x3
	mov x3, x4
	mov x4, x5
	mov x5, x6
	svc #0
	ret

// void enter_guest(void *entry, void *sp)
	.globl enter_guest
	.type enter_guest,%function
enter_guest:
	mov x16, x0
	mov sp, x1
	mov x0, #0
	mov x1, #0
	mov x2, #0
	mov x3, #0
	mov x29, #0
	mov x30, #0
	br x16

// long rl_call_on_stack(void *top, void *fn, long a0, long a1, long a2)
// Run fn(a0, a1, a2) with sp switched to top — a supervisor-owned trap stack
// (137 fix yi', in run64.c) — then restore the caller's sp and return fn's
// result. Before the switch only caller-saved scratch (x9, x10, x16) is
// touched and the old stack sees exactly two stores (32 bytes: the frame
// pair and the saved sp) — the least a switch can cost a thread that may
// already be standing at the bottom of the guest's 16 KB alternate signal
// stack. The saved sp is read back through x29, which fn preserves like
// every callee-saved register; the frame pair is reloaded by address from
// the restored sp, never by a frame walk on the foreign stack.
	.globl rl_call_on_stack
	.type rl_call_on_stack,%function
rl_call_on_stack:
	mov x9, sp
	mov x10, x0
	mov x16, x1
	stp x29, x30, [sp, #-16]!
	str x9, [sp, #-16]!
	mov x29, sp
	mov sp, x10
	mov x0, x2
	mov x1, x3
	mov x2, x4
	blr x16
	ldr x9, [x29]
	mov sp, x9
	ldp x29, x30, [sp, #-16]
	ret

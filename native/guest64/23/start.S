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

// long watch_clone(long flags, void *stack, void (*fn)(void))
// clone() for the ptrace watcher thread (run64.c): the parent gets the new tid and returns
// normally; the child thread starts on the stack it was given, calls fn and exits without
// returning — it must never touch the caller's frame, which lives on the parent's stack.
	.globl watch_clone
	.type watch_clone,%function
watch_clone:
	stp x19, x30, [sp, #-16]!
	mov x19, x2
	mov x2, #0
	mov x3, #0
	mov x4, #0
	mov x5, #0
	mov x8, #220
	svc #0
	cbnz x0, 1f
	blr x19
	mov x8, #93
	svc #0
	brk #1
1:	ldp x19, x30, [sp], #16
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

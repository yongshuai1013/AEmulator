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

// void sigsys_entry(int sig, void *info, void *uc)
// The SIGSYS handler's first instructions (run64.c installs this, not the C body). The trap
// chain used to run entirely on the trapping thread's own stack and a thread with too little
// stack left died in the doorway (the Magisk daemon, 2026-11-26). Here nothing touches the
// interrupted stack: the arguments and the return address are parked in callee-saved
// registers, trap_stack_top (run64.c) hands back this thread's dedicated trap stack, and the
// C body runs there. The interrupted sp is restored before returning, so the kernel's signal
// frame — which lives on the interrupted stack — is read back exactly as it was left. If the
// thread is already on its trap stack (a nested delivery, or kernel delivery onto the
// sigaltstack over the same memory) the body runs where it is; if no slot is left, it runs
// on the interrupted stack, which is the pre-batch behaviour.
	.globl sigsys_entry
	.type sigsys_entry,%function
sigsys_entry:
	mov x19, x0
	mov x20, x1
	mov x21, x2
	mov x22, sp
	mov x23, x30
	mov x8, #178
	svc #0
	bl trap_stack_top
	cbz x0, 2f
	sub x9, x0, #65536
	cmp x22, x9
	b.lo 1f
	cmp x22, x0
	b.lo 2f
1:	mov sp, x0
2:	mov x0, x19
	mov x1, x20
	mov x2, x21
	bl on_sigsys_body
	mov sp, x22
	mov x30, x23
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

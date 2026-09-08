package cn.elonzh.hanppie.robot

/** Ordered startup transcript from lab/protocol.py; not inferred SDK commands. */
data class SetupCommand(val receiver: Int, val set: Int, val id: Int,
                        val payload: String = "", val flags: String = "0000", val control: Boolean = false)

val connectionSetup: List<SetupCommand> = listOf(
    SetupCommand(0x28, 0, 1),
    SetupCommand(0x28, 0x3f, 0xfe, "00"),
    SetupCommand(0x28, 0, 0x4f, "0100000000ffffffff", "4036"),
    SetupCommand(0x28, 0, 0x4f, "01d4030000ffffffff"),
    SetupCommand(7, 7, 0x30, "4a5000004a5000000100"),
    SetupCommand(0x28, 0, 0x4f, "01a8070000ffffffff"),
    SetupCommand(0x28, 0, 0x4f, "017c0b0000ffffffff"),
    SetupCommand(9, 1, 4, control = true),
    SetupCommand(0x28, 0, 0x4f, "01500f0000ffffffff"),
    SetupCommand(0x28, 0, 0x4f, "0124130000ffffffff", "6000"),
    SetupCommand(0x28, 0, 0x4f, "01f8160000ffffffff"),
    SetupCommand(0x28, 0, 0x4f, "01cc1a0000ffffffff"),
    SetupCommand(9, 1, 4, control = true),
    SetupCommand(9, 1, 4, control = true),
    SetupCommand(9, 1, 4, control = true),
    SetupCommand(9, 0x48, 1, "0200000003"),
    SetupCommand(9, 0x48, 4, "000201"),
) + List(2) { SetupCommand(9, 0x48, 3,
    "02010000059f22626809000200c49ac5c409000200fd7b4c7809000200ceceb7ee090002009c00a449090002000100") }

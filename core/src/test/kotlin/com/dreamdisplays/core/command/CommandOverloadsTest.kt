package com.dreamdisplays.core.command

import com.mojang.brigadier.tree.CommandNode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Bedrock clients (via Geyser) refuse a command with more than 250 overloads (full argument paths). Only the `/display`
 * node from [DisplayCommandTree.root] is sent to clients, so it must stay far below that.
 */
class CommandOverloadsTest {
    private fun <S> paths(node: CommandNode<S>): Int =
        (if (node.command != null || node.children.isEmpty()) 1 else 0) + node.children.sumOf { paths(it) }

    @Test
    fun `the client-visible display tree stays under the Bedrock overload limit`() {
        val tree = DisplayCommandTree(
            FakePlatform(),
            DisplayCommands.ALL.filter { !it.helpOnly && it.id != "help" }.associate { it.id to Handler<FakeSource> { } },
        )
        val count = paths(tree.root())
        assertTrue(count <= 250, "/display has $count overloads; Bedrock clients refuse more than 250")
    }
}

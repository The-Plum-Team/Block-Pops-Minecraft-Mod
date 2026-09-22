"""Security and portability regression tests for BlockPops automation."""

import os

# The build gate exports BLOCKPOPS_TESTED_SHA so the real release bundle is bound
# to the commit under test. These tests build throwaway repositories with HEADs
# of their own, so inheriting that value makes every one of them disagree with
# it. A test that exercises the binding sets the variable itself.
os.environ.pop("BLOCKPOPS_TESTED_SHA", None)

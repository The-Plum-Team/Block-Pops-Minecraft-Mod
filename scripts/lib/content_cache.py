"""Bounded in-process memo for work that is a pure function of exact input bytes.

Keys are the SHA-256 of the bytes plus every parameter the result depends on, so a hit means
these exact bytes already passed the same real decode and checks in this process. Only
successful results are stored: a rejection always runs the real path again and raises with its
own caller's label. Anything that depends on the filesystem (lstat, O_NOFOLLOW, sizes, races)
stays outside the cached function and runs on every call.
"""

from __future__ import annotations

import copy
import hashlib
from collections import OrderedDict
from collections.abc import Callable, Hashable
from typing import Any, TypeVar

T = TypeVar("T")


class ContentCache:
    def __init__(self, *, entries: int, max_bytes: int | None = None) -> None:
        if entries <= 0 or (max_bytes is not None and max_bytes <= 0):
            raise ValueError("content cache bounds must be positive")
        self._entries = entries
        self._max_bytes = max_bytes
        self._bytes = 0
        self._values: OrderedDict[tuple[Hashable, ...], tuple[Any, int]] = OrderedDict()

    def __len__(self) -> int:
        return len(self._values)

    @property
    def stored_bytes(self) -> int:
        return self._bytes

    def clear(self) -> None:
        self._values.clear()
        self._bytes = 0

    def get_or_compute(
        self,
        data: bytes,
        parameters: tuple[Hashable, ...],
        compute: Callable[[], T],
        *,
        size: Callable[[T], int] = lambda _value: 0,
    ) -> T:
        """Return ``compute()`` for these bytes and parameters, reusing an earlier success.

        Mutable results are deep-copied on the way in and out, so no caller can alter what a
        later hit returns. With ``max_bytes``, ``size`` weighs each result against that budget; one
        heavier than the whole budget is returned but never stored.
        """

        key = (hashlib.sha256(data).hexdigest(), len(data), *parameters)
        cached = self._values.get(key)
        if cached is not None:
            self._values.move_to_end(key)
            return copy.deepcopy(cached[0])
        value = compute()
        weight = size(value)
        if self._max_bytes is None or weight <= self._max_bytes:
            self._values[key] = (copy.deepcopy(value), weight)
            self._bytes += weight
            while len(self._values) > self._entries or (
                self._max_bytes is not None and self._bytes > self._max_bytes
            ):
                _, (_, evicted) = self._values.popitem(last=False)
                self._bytes -= evicted
        return value

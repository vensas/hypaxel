"""Starlette / FastAPI integration.

::

    from fastapi import FastAPI
    from hypaxel.starlette import XlsxResponse
    import hypaxel

    app = FastAPI()

    @app.get("/orders.xlsx")
    async def orders():
        rows = await db.stream("SELECT id, customer, amount FROM orders")   # async iterable
        return XlsxResponse(hypaxel.astream_rows(rows, header=["Id", "Customer", "Amount"]), filename="orders.xlsx")

Sync generators (``stream_rows`` / ``stream_records`` / ``stream_dataframe``) are iterated in
Starlette's thread pool, so CPU work never blocks the event loop.
"""

from __future__ import annotations

from collections.abc import AsyncIterable, Iterable, Mapping
from typing import Optional, Union
from urllib.parse import quote

from starlette.background import BackgroundTask
from starlette.responses import StreamingResponse

from . import CONTENT_TYPE

__all__ = ["XlsxResponse", "content_disposition"]


def content_disposition(filename: str) -> str:
    """``attachment`` header with an ASCII fallback and an RFC 5987 UTF-8 name."""
    if not filename.lower().endswith(".xlsx"):
        filename += ".xlsx"
    fallback = "".join(c if c.isascii() and c.isprintable() and c not in '"\\' else "_" for c in filename)
    return f"attachment; filename=\"{fallback}\"; filename*=UTF-8''{quote(filename, safe='')}"


class XlsxResponse(StreamingResponse):
    """A streaming XLSX download."""

    def __init__(
        self,
        content: Union[Iterable[bytes], AsyncIterable[bytes]],
        filename: Optional[str] = None,
        status_code: int = 200,
        headers: Optional[Mapping[str, str]] = None,
        background: Optional[BackgroundTask] = None,
    ) -> None:
        merged = {"cache-control": "no-store", **(headers or {})}
        if filename:
            merged["content-disposition"] = content_disposition(filename)
        super().__init__(content, status_code=status_code, headers=merged, media_type=CONTENT_TYPE, background=background)

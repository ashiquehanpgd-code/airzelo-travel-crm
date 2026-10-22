class ApiError(Exception):
    status = 400

    def __init__(self, message, status=None, code=None, field=None, details=None):
        super().__init__(message)
        self.message = message
        if status is not None:
            self.status = status
        self.code = code or "error"
        self.field = field
        self.details = details

    def to_dict(self):
        d = {"error": self.message, "code": self.code}
        if self.field:
            d["field"] = self.field
        if self.details is not None:
            d["details"] = self.details
        return d


class ValidationError(ApiError):
    def __init__(self, message, field=None, details=None):
        super().__init__(message, status=400, code="validation", field=field, details=details)


class NotFound(ApiError):
    def __init__(self, message="Not found"):
        super().__init__(message, status=404, code="not_found")


class Conflict(ApiError):
    def __init__(self, message, code="conflict", details=None):
        super().__init__(message, status=409, code=code, details=details)


class Unauthorized(ApiError):
    def __init__(self, message="Please sign in"):
        super().__init__(message, status=401, code="unauthorized")


class Forbidden(ApiError):
    def __init__(self, message="Forbidden"):
        super().__init__(message, status=403, code="forbidden")

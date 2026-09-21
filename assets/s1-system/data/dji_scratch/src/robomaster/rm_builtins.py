import math


def rmround(value, pre=0):
    value = int(value * math.pow(10, pre + 1))
    point = value % 10
    value = int(value / 10)
    if point >= 5:
        value += 1
    value /= math.pow(10, pre)
    return value


def rmexit(*args, **Kw):
    raise Exception('Exit')


class RmList():
    # RmList, list index begins with 1 instead of 0.
    # Adapt to Scratch.
    def __init__(self, initlist=None):
        self.data = []
        if initlist is not None:
            if type(initlist) == type(self.data):
                self.data[:] = initlist
            elif isinstance(initlist, RmList):
                self.data[:] = initlist.data[:]
            else:
                self.data = list(initlist)

    def __repr__(self):
        return repr(self.data)

    def __lt__(self, other):
        return self.data < self.__cast(other)

    def __le__(self, other):
        return self.data <= self.__cast(other)

    def __eq__(self, other):
        return self.data == self.__cast(other)

    def __gt__(self, other):
        return self.data > self.__cast(other)

    def __ge__(self, other):
        return self.data >= self.__cast(other)

    def __cast(self, other):
        return other.data if isinstance(other, RmList) else other

    def __contains__(self, item):
        return item in self.data

    def __len__(self):
        return len(self.data)

    def __getitem__(self, i):
        i -= 1
        return self.data[i]

    def __setitem__(self, i, item):
        i -= 1
        self.data[i] = item

    def __delitem__(self, i):
        i -= 1
        del self.data[i]

    def __add__(self, other):
        if isinstance(other, UserList):
            return self.__class__(self.data + other.data)
        elif isinstance(other, type(self.data)):
            return self.__class__(self.data + other)
        return self.__class__(self.data + list(other))

    def __radd__(self, other):
        if isinstance(other, RmList):
            return self.__class__(other.data + self.data)
        elif isinstance(other, type(self.data)):
            return self.__class__(other + self.data)
        return self.__class__(list(other) + self.data)

    def __iadd__(self, other):
        if isinstance(other, RmList):
            self.data += other.data
        elif isinstance(other, type(self.data)):
            self.data += other
        else:
            self.data += list(other)
        return self

    def __mul__(self, n):
        return self.__class__(self.data * n)

    def __rmul__(self, n):
        return self.__mul__(n)

    def __imul__(self, n):
        self.data *= n
        return self

    def append(self, item):
        self.data.append(item)

    def insert(self, i, item):
        i -= 1
        self.data.insert(i, item)

    def pop(self, i=-1):
        if i != -1:
            i -= 1
        return self.data.pop(i)

    def remove(self, item):
        self.data.remove(item)

    def clear(self):
        self.data.clear()

    def copy(self):
        return self.__class__(self)

    def count(self, item):
        return self.data.count(item)

    def index(self, item, *args):
        return self.data.index(item, *args) + 1

    def reverse(self):
        self.data.reverse()

    def sort(self, *args, **kwds):
        self.data.sort(*args, **kwds)

    def extend(self, other):
        if isinstance(other, RmList):
            self.data.extend(other.data)
        else:
            self.data.extend(other)

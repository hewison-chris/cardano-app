module DateTime exposing (format, fromInputValue, toInputValue)

{-| Conversions between POSIX milliseconds and the `YYYY-MM-DDTHH:MM` strings of an
`<input type="datetime-local">`, in the browser's time zone.

`tzOffset` is JavaScript's `new Date().getTimezoneOffset()`: minutes to add to local time to get UTC.

-}

import Time


{-| Parse `YYYY-MM-DDTHH:MM[:SS]` (local time) to POSIX milliseconds.
-}
fromInputValue : Int -> String -> Maybe Int
fromInputValue tzOffset str =
    case String.split "T" (String.trim str) of
        [ date, time ] ->
            case ( List.map String.toInt (String.split "-" date), List.map String.toInt (String.split ":" time) ) of
                ( [ Just y, Just m, Just d ], (Just hh) :: (Just mm) :: rest ) ->
                    let
                        ss =
                            case rest of
                                [ Just s ] ->
                                    s

                                _ ->
                                    0

                        seconds =
                            daysFromCivil y m d * 86400 + hh * 3600 + mm * 60 + ss + tzOffset * 60
                    in
                    if m < 1 || m > 12 || d < 1 || d > 31 || hh > 23 || mm > 59 then
                        Nothing

                    else
                        Just (seconds * 1000)

                _ ->
                    Nothing

        _ ->
            Nothing


{-| Format for a `datetime-local` input.
-}
toInputValue : Time.Zone -> Time.Posix -> String
toInputValue zone t =
    datePart zone t ++ "T" ++ timePart zone t


{-| Human readable `YYYY-MM-DD HH:MM`.
-}
format : Time.Zone -> Time.Posix -> String
format zone t =
    datePart zone t ++ " " ++ timePart zone t



-- INTERNAL


datePart : Time.Zone -> Time.Posix -> String
datePart zone t =
    String.fromInt (Time.toYear zone t)
        ++ "-"
        ++ pad2 (monthNumber (Time.toMonth zone t))
        ++ "-"
        ++ pad2 (Time.toDay zone t)


timePart : Time.Zone -> Time.Posix -> String
timePart zone t =
    pad2 (Time.toHour zone t) ++ ":" ++ pad2 (Time.toMinute zone t)


pad2 : Int -> String
pad2 n =
    String.padLeft 2 '0' (String.fromInt n)


{-| Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm).
-}
daysFromCivil : Int -> Int -> Int -> Int
daysFromCivil year month day =
    let
        y =
            if month <= 2 then
                year - 1

            else
                year

        era =
            (if y >= 0 then
                y

             else
                y - 399
            )
                // 400

        yoe =
            y - era * 400

        mp =
            modBy 12 (month + 9)

        doy =
            (153 * mp + 2) // 5 + day - 1

        doe =
            yoe * 365 + yoe // 4 - yoe // 100 + doy
    in
    era * 146097 + doe - 719468


monthNumber : Time.Month -> Int
monthNumber month =
    case month of
        Time.Jan ->
            1

        Time.Feb ->
            2

        Time.Mar ->
            3

        Time.Apr ->
            4

        Time.May ->
            5

        Time.Jun ->
            6

        Time.Jul ->
            7

        Time.Aug ->
            8

        Time.Sep ->
            9

        Time.Oct ->
            10

        Time.Nov ->
            11

        Time.Dec ->
            12

